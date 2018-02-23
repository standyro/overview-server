package com.overviewdocs.ingest.pipeline

import akka.stream.scaladsl.Source
import akka.util.ByteString
import scala.concurrent.{ExecutionContext,Future,Promise}

import com.overviewdocs.ingest.File2Writer
import com.overviewdocs.models.File2

/** Converts a File2 from WRITTEN to PROCESSED and outputs its WRITTEN
  * children.
  */
class Step(logic: StepLogic, file2Writer: File2Writer) {
  /** Converts a File2 from WRITTEN to PROCESSED and outputs its WRITTEN
    * children as they come. (Just the children! Not itself.)
    *
    * `onProgress()` will be called every time the StepLogic outputs Progress
    * fragments. `canceled` will be passed to the StepLogic.
    *
    * The return value is the input File2, PROCESSED, as written to the
    * database. It will have `nChildren` and may have a `processingError`. Even
    * after cancellation, the StepLogic will complete; in that case,
    * `processingError` will become `"canceled"`.
    */
  def process(
    parentFile2: File2,
    onProgress: Double => Unit,
    canceled: Future[akka.Done]
  )(implicit ec: ExecutionContext): Source[File2, Future[File2]] = {
    // The retval's materialized value
    val writtenParentPromise = Promise[File2]()

    /*
     * We process the incoming stream of StepOutputFragments, passing each to
     * a (side-effect producing) File2Writer method and emitting each child
     * File2 when it's ready -- including one final emit after the input stream
     * completes.
     *
     * akka-stream doesn't have exactly what we want. Its foldAsync() is great
     * for generating side-effects and maintaining state, but it doesn't emit
     * anything. scanAsync() is great for emitting, but it _always_ emits.
     *
     * So we compose: onFragment emits an Option[element] _and_ the State.
     * state.
     */

    sealed trait State
    case object Start extends State
    case class AtChild(child: File2, isWritable: Boolean) extends State
    case object End extends State

    type ScanResult = Tuple2[Option[File2], State]

    def onFragment(lastEmitAndState: ScanResult, fragment: StepOutputFragment): Future[ScanResult] = {
      val state = lastEmitAndState._2
      (state, fragment) match {
        case (_, p: StepOutputFragment.Progress) => { onProgress(p.fraction); ignore(state) }
        case (End, _) => ignore(state) // we've marked the parent as processed: garbage can't change our minds
        case (Start, h: StepOutputFragment.File2Header) => createChild(None, false, h)
        case (Start, e: StepOutputFragment.EndFragment) => end(None, false, e)
        case (Start, f) => unexpectedFragment(f, None)
        case (AtChild(child, _), StepOutputFragment.Blob(stream)) => addBlob(child, stream)
        case (AtChild(child, _), StepOutputFragment.InheritBlob) => inheritBlob(child)
        case (AtChild(child, isWritable), StepOutputFragment.Text(stream)) => addText(child, isWritable, stream)
        case (AtChild(child, isWritable), StepOutputFragment.Thumbnail(ct, stream)) => addThumbnail(child, isWritable, ct, stream)
        case (AtChild(child, isWritable), h: StepOutputFragment.File2Header) => createChild(Some(child), isWritable, h)
        case (AtChild(child, isWritable), e: StepOutputFragment.EndFragment) => end(Some(child), isWritable, e)
      }
    }

    def ignore(lastState: State): Future[ScanResult] = {
      Future.successful((None, lastState))
    }

    def error(message: String, currentChild: Option[File2]): Future[ScanResult] = {
      currentChild match {
        case Some(child) => {
          // We'll delete the current child File2: it isn't WRITTEN
          val nPreviousChildren = child.indexInParent
          for {
            _ <- file2Writer.deleteFile2(child)
            _ <- setParentProcessed(nPreviousChildren, Some(message))
          } yield (None, End)
        }
        case None => {
          for {
            _ <- setParentProcessed(0, Some(message))
          } yield (None, End)
        }
      }
    }

    def logicError(message: String, currentChild: Option[File2]): Future[ScanResult] = {
      error("logic error: " + message, currentChild)
    }

    def unexpectedFragment(f: StepOutputFragment, currentChild: Option[File2]): Future[ScanResult] = {
      logicError("unexpected fragment " + f.getClass.toString, currentChild)
    }

    def createChild(lastChild: Option[File2], lastChildIsWritable: Boolean, header: StepOutputFragment.File2Header): Future[ScanResult] = {
      // If header is invalid (has wrong indexInParent), move to error state
      // Otherwise, emit lastChild (which may be None) and move to AtChild
      if (lastChild.nonEmpty && !lastChildIsWritable) {
        unexpectedFragment(header, lastChild)
      } else {
        for {
          lastChildWritten <- lastChild match {
            case Some(child) => file2Writer.setWritten(child).map(Some.apply _)
            case None => Future.successful(None)
          }
          nextChild <- file2Writer.createChild(
            parentFile2,
            lastChild.map(_.indexInParent + 1).getOrElse(0),
            header.filename,
            header.contentType,
            header.metadataJson,
            header.pipelineOptions
          )
        } yield (lastChildWritten, AtChild(nextChild, false))
      }
    }

    def addBlob(child: File2, data: Source[ByteString, _]): Future[ScanResult] = {
      for {
        writtenChild <- file2Writer.writeBlob(child, data)
      } yield (None, AtChild(writtenChild, true))
    }

    def addThumbnail(child: File2, isWritable: Boolean, contentType: String, data: Source[ByteString, _]): Future[ScanResult] = {
      for {
        writtenChild <- file2Writer.writeThumbnail(child, contentType, data)
      } yield (None, AtChild(writtenChild, isWritable))
    }

    def addText(child: File2, isWritable: Boolean, data: Source[ByteString, _]): Future[ScanResult] = {
      for {
        writtenChild <- file2Writer.writeText(child, data)
      } yield (None, AtChild(writtenChild, isWritable))
    }

    def inheritBlob(child: File2): Future[ScanResult] = {
      if (child.indexInParent != 0) {
        logicError("tried to inherit blob when indexInParent!=0", Some(child))
      } else {
        for {
          writtenChild <- file2Writer.writeInheritBlobFromParent(child, parentFile2)
        } yield (None, AtChild(writtenChild, true))
      }
    }

    def end(lastChild: Option[File2], isWritable: Boolean, fragment: StepOutputFragment.EndFragment): Future[ScanResult] = {
      (lastChild, isWritable, fragment) match {
        case (_, _, StepOutputFragment.FileError(message)) => error(message, lastChild)
        case (_, _, StepOutputFragment.StepError(ex)) => error("step error: " + ex.getMessage, lastChild)
        case (_, _, StepOutputFragment.Canceled) => error("canceled", lastChild)
        case (Some(child), false, _) => logicError("tried to write child without blob data", lastChild)
        case (Some(child), true, StepOutputFragment.Done) => for {
          lastChildWritten <- file2Writer.setWritten(child)
          _ <- setParentProcessed(child.indexInParent + 1, None)
        } yield (Some(lastChildWritten), End)
        case (None, _, StepOutputFragment.Done) => for {
          _ <- setParentProcessed(0, None)
        } yield (None, End)
      }
    }

    def setParentProcessed(nChildren: Int, error: Option[String]): Future[Unit] = {
      for {
        writtenParent <- file2Writer.setProcessed(parentFile2, nChildren, error)
      } yield {
        writtenParentPromise.success(writtenParent)
      }
    }

    val children: Source[File2, akka.NotUsed] = logic.processIntoFragments(parentFile2, canceled)
      .scanAsync((None, Start): ScanResult)(onFragment)  // (MaybeChild, State) pairs
      .collect(Function.unlift((r: ScanResult) => r._1)) // just Some(child) elements

    children.mapMaterializedValue(_ => writtenParentPromise.future)
  }
}
