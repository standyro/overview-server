package com.overviewdocs.ingest.models

import scala.concurrent.Future

import com.overviewdocs.models.{BlobStorageRef,File2}

/** File2: we know it has been Created, but nothing else.
  *
  * This holds just enough information to:
  *
  * 1. Convert to a WrittenFile2 during resume.
  * 2. Convert to a WrittenFile2 while writing to the database.
  */
case class CreatedFile2(
  id: Long,
  documentSetId: Long,                     // for ingest, later
  rootId: Option[Long],                    // to create children while processing
  parentId: Option[Long],                  // to create children while processing
  indexInParent: Int,                      // to delete, in case of processing error
  filename: String,                        // for processing
  contentType: String,                     // for processing
  languageCode: String,                    // for processing
  metadata: File2.Metadata,                // to create children while processing
  pipelineOptions: File2.PipelineOptions,  // for processing
  blobOpt: Option[BlobStorageRefWithSha1], // for processing
  ownsBlob: Boolean,                       // for delete/resume
  thumbnailLocationOpt: Option[String],    // for delete/resume
  ownsThumbnail: Boolean,                  // for delete/resume
  onProgress: Double => Unit,              // for processing
  canceled: Future[akka.Done]              // for processing
) {
  def asWrittenFile2Opt: Option[WrittenFile2] = blobOpt match {
    case Some(blob) => Some(WrittenFile2(id, documentSetId, rootId, parentId, filename, contentType, languageCode, metadata, pipelineOptions, blob, onProgress, canceled))
    case None => None
  }

  def asProcessedFile2(nChildren: Int, nIngestedChildren: Int): ProcessedFile2 = {
    ProcessedFile2(id, documentSetId, parentId, nChildren, nIngestedChildren)
  }
}