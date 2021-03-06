package controllers.backend

import akka.actor.ActorSystem
import akka.serialization.SerializationExtension
import akka.util.{ByteString,Timeout}
import com.google.inject.ImplementedBy
import redis.RedisClient
import java.nio.ByteBuffer
import java.util.UUID
import javax.inject.Inject
import scala.collection.immutable
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.Future
import scala.util.{Try,Success,Failure}

import com.overviewdocs.util.Logger
import models.{InMemorySelection,Selection,SelectionRequest,SelectionWarning}
import models.pagination.{Page,PageInfo,PageRequest}
import modules.RedisModule

/** A store of Selections.
  *
  * A Selection is a list of document IDs. It is first created via
  * DocumentSelectionBackend.createSelection(), but it may later be persisted.
  *
  * We persist Selections so we can paginate over them. (We can't paginate using
  * query params, because queries run at different times will give different
  * results -- and because querying is slower than reloading a Selection.)
  *
  * Think of this as a cache. If you findOrCreate() on a request that refers
  * back to a previously-cached selection, you'll get the cached selection
  * back.
  */
@ImplementedBy(classOf[RedisSelectionBackend])
trait SelectionBackend extends Backend {
  protected val documentSelectionBackend: DocumentSelectionBackend

  /** Converts a SelectionRequest to a new Selection.
    *
    * Sorting on a non-default column can take a long time. If this call needs
    * a sort, onProgress() will be called repeatedly with numbers between 0.0
    * and 1.0 as sorting progresses.
    */
  def create(userEmail: String, request: SelectionRequest, onProgress: Double => Unit): Future[Selection]

  /** Returns an existing Selection, if it exists. */
  def find(documentSetId: Long, selectionId: UUID): Future[Option[Selection]]

  /** Finds an existing Selection, or returns a new one.
    *
    * This takes more inputs than simple find() or create(). It does this:
    *
    * 1. If maybeSelectionId exists, search for that Selection and return it.
    * 2. Otherwise, search for a Selection based on request.hash and return it.
    * 3. Otherwise, call create().
    *
    * Sorting on a non-default column can take a long time. If this call needs
    * a sort, onProgress() will be called repeatedly with numbers between 0.0
    * and 1.0 as sorting progresses.
    */
  def findOrCreate(userEmail: String, request: SelectionRequest, maybeSelectionId: Option[UUID], onProgress: Double => Unit): Future[Selection]
}

class NullSelectionBackend @Inject() (
  override val documentSelectionBackend: DocumentSelectionBackend
) extends SelectionBackend {

  override def create(userEmail: String, request: SelectionRequest, onProgress: Double => Unit) = {
    documentSelectionBackend.createSelection(request, onProgress)
  }

  override def findOrCreate(userEmail: String, request: SelectionRequest, maybeSelectionId: Option[UUID], onProgress: Double => Unit) = {
    create(userEmail, request, onProgress)
  }

  override def find(documentSetId: Long, selectionId: UUID) = Future.successful(None)
}

/** Stores Selections in Redis.
  *
  * We store the following keys:
  *
  * * `selection:[documentSetId]:by-user-hash:[email]:[hash of query params]`:
  *   A (String) Selection ID. The `documentSetId` is for consistency (and
  *   sharding); the `email` is so that two users viewing the same document set
  *   don't affect one another's sessions; and the `hash` is the part that
  *   ensures that two subsequent requests for the same query parameters can
  *   return the same Selection.
  * * `selection:[documentSetId]:by-id:[id]:document-ids`: A (String) byte
  *   array of 64-bit document IDs.
  * * `selection:[documentSetId]:by-id:[id]:warnings`: A Serialized
  *   List[SelectionWarning].
  *
  * Selections all expire. find() and findOrCreate() reset the expiry time.
  */
class RedisSelectionBackend @Inject() (
  override val documentSelectionBackend: DocumentSelectionBackend,
  val actorSystem: ActorSystem,
  val redisModule: RedisModule
) extends SelectionBackend {
  protected val redis: RedisClient = redisModule.client

  private val logger: Logger = Logger.forClass(getClass)

  private[backend] val ExpiresInSeconds: Int = 60 * 60 // Used in unit tests, too
  private val SizeOfLong = 8

  private val serialization = SerializationExtension(actorSystem)

  private def requestHashKey(userEmail: String, request: SelectionRequest) = {
    s"selection:${request.documentSetId}:by-user-hash:${userEmail}:${request.hash}"
  }

  private def documentIdsKey(documentSetId: Long, selectionId: UUID) = {
    s"selection:${documentSetId}:by-id:${selectionId}:document-ids"
  }

  private def buildWarningsKey(documentSetId: Long, selectionId: UUID) = {
    s"selection:${documentSetId}:by-id:${selectionId}:warnings"
  }

  case class RedisSelection(val documentSetId: Long, override val id: UUID, override val warnings: List[SelectionWarning]) extends Selection {
    private def throwMissingError = throw new Exception("document IDs disappeared even though we _just_ reset their expire time")

    private val key = documentIdsKey(documentSetId, id)

    override def getDocumentCount: Future[Int] = {
      redis
        .strlen(key)
        .map((n: Long) => (n / SizeOfLong).toInt)
    }

    private def getDocumentIdsVector(offset: Int, limit: Int): Future[Vector[Long]] = {
      redis
        .getrange[Array[Byte]](key, offset * SizeOfLong, (offset + limit) * SizeOfLong - 1)
        .map(_.getOrElse(throwMissingError))
        .map { (bytes: Array[Byte]) =>
          val buf = ByteBuffer.wrap(bytes).asLongBuffer
          val longs = Array.fill(buf.capacity)(0L)
          buf.get(longs)
          longs.toVector
        }
    }

    private def pageRequestToDocumentIds(pageRequest: PageRequest, total: Int): Future[Vector[Long]] = {
      if (pageRequest.reverse) {
        val (offset, limit) = if (pageRequest.offset + pageRequest.limit > total) {
          (0, total - pageRequest.offset)
        } else {
          (total - pageRequest.offset - pageRequest.limit, pageRequest.limit)
        }
        getDocumentIdsVector(offset, limit).map(_.reverse)
      } else {
        getDocumentIdsVector(pageRequest.offset, pageRequest.limit)
      }
    }

    override def getDocumentIds(page: PageRequest): Future[Page[Long]] = {
      for {
        total <- getDocumentCount
        longs <- pageRequestToDocumentIds(page, total.toInt)
      } yield Page(longs, PageInfo(page, total.toInt))
    }

    override def getAllDocumentIds: Future[Vector[Long]] = {
      getDocumentIdsVector(0, Int.MaxValue / SizeOfLong)
    }
  }

  // pudo's 2M documents led to:
  // play.api.UnexpectedException: Unexpected exception[AskTimeoutException: Ask timed out on [Actor[akka://RedisPlugin/user/redis-client-0#429941718]] after [1000 ms]]
  // ... and after that, every future request for documents failed.
  //
  // The delay comes from the actor not responding. The actor doesn't respond
  // because the CPU (just one, on production) is too busy to attend to it.
  // This isn't a legitimate exception; expanding timeout to 60s.
  private implicit val timeout: Timeout = Timeout(60, java.util.concurrent.TimeUnit.SECONDS)

  private def encodeDocumentIds(documentIds: immutable.Seq[Long]): Array[Byte] = {
    val buffer = ByteBuffer.allocate(documentIds.length * SizeOfLong)
    documentIds.foreach(buffer.putLong)
    buffer.array
  }

  private def findAndExpireSelectionIdByHash(userEmail: String, request: SelectionRequest): Future[Option[UUID]] = {
    val hashKey = requestHashKey(userEmail, request)

    for {
      _ <- redis.expire(hashKey, ExpiresInSeconds)
      maybeUuidString: Option[String] <- redis.get[String](hashKey)
    } yield maybeUuidString.map(UUID.fromString)
  }

  private def findAndExpireSelectionById(documentSetId: Long, id: UUID): Future[Option[Selection]] = {
    val idsKey = documentIdsKey(documentSetId, id)
    val warningsKey = buildWarningsKey(documentSetId, id)

    for {
      idsExist <- redis.expire(idsKey, ExpiresInSeconds)
      _ <- redis.expire(warningsKey, ExpiresInSeconds)
      maybeWarningsString: Option[ByteString] <- redis.get(warningsKey)
    } yield (idsExist, maybeWarningsString) match {
      case (true, Some(warningsString)) => {
        parseWarnings(warningsString) match {
          case Success(warnings) => Some(RedisSelection(documentSetId, id, warnings))
          case Failure(_) => None
        }
      }
      case _ => None
    }
  }

  private def parseWarnings(byteString: ByteString): Try[List[SelectionWarning]] = {
    serialization.deserialize(byteString.toArray, classOf[List[SelectionWarning]])
  }

  private def encodeWarnings(warnings: List[SelectionWarning]): ByteString = {
    val byteArray = serialization.serialize(warnings).get
    ByteString(byteArray)
  }

  private def writeSelection(userEmail: String, request: SelectionRequest, selection: InMemorySelection): Future[Unit] = {
    val byUserHashKey = requestHashKey(userEmail, request)
    val byIdKey = documentIdsKey(request.documentSetId, selection.id)
    val warningsKey = buildWarningsKey(request.documentSetId, selection.id)

    for {
      _ <- redis.setex(byUserHashKey, ExpiresInSeconds, selection.id.toString)
      _ <- redis.setex(byIdKey, ExpiresInSeconds, encodeDocumentIds(selection.documentIds))
      _ <- redis.setex(warningsKey, ExpiresInSeconds, encodeWarnings(selection.warnings))
    } yield ()
  }

  override def create(userEmail: String, request: SelectionRequest, onProgress: Double => Unit) = {
    for {
      selection <- documentSelectionBackend.createSelection(request, onProgress)
      _ <- writeSelection(userEmail, request, selection)
    } yield selection // return InMemorySelection, not a RedisSelection -- it's faster
  }

  override def find(documentSetId: Long, selectionId: UUID) = {
    findAndExpireSelectionById(documentSetId, selectionId)
  }

  override def findOrCreate(userEmail: String, request: SelectionRequest, maybeSelectionId: Option[UUID], onProgress: Double => Unit) = {
    for {
      // 1. Get selection ID if at all possible
      selectionId: Option[UUID] <- (maybeSelectionId
        .map(id => Future.successful(Some(id)))
        .getOrElse(findAndExpireSelectionIdByHash(userEmail, request)))

      // 2. Read Selection from Redis if we have an ID
      selectionById: Option[Selection] <- (selectionId
        .map(id => findAndExpireSelectionById(request.documentSetId, id))
        .getOrElse(Future.successful(None)))

      // 3. Return the selection, or create a new one if we couldn't find one
      selection: Selection <- (selectionById
        .map(s => Future.successful(s))
        .getOrElse(create(userEmail, request, onProgress)))
    } yield selection
  }
}
