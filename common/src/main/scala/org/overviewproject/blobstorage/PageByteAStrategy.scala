package org.overviewproject.blobstorage

import java.io.InputStream
import play.api.libs.iteratee.Enumerator
import scala.concurrent.{ Future, blocking }
import scala.concurrent.ExecutionContext.Implicits.global
import org.overviewproject.database.Database
import org.overviewproject.models.tables.Pages
import org.overviewproject.database.Slick.simple._
import java.io.ByteArrayInputStream

trait PageByteAStrategy extends BlobStorageStrategy {

  protected def db[A](block: Session => A): Future[A] = Future {
    blocking {
      Database.withSlickSession { session =>
        block(session)
      }
    }
  }

  private val LocationRegex = """^pagebytea:(\d+)$""".r
  private case class Location(pageId: Long)

  private def stringToLocation(locationString: String): Location = locationString match {
    case LocationRegex(pageId) => Location(pageId.toLong)
    case _ => throw new IllegalArgumentException(s"Invalid location string: '${locationString}'")
  }

  override def get(locationString: String): Future[Enumerator[Array[Byte]]] = {
    val location = stringToLocation(locationString)

    db { implicit session =>

      val q = Pages.filter(_.id === location.pageId)

      val data = for {
        p <- q.firstOption
      } yield {
        val data = p.data.getOrElse(Array.empty)
        val dataStream = new ByteArrayInputStream(data)
        Enumerator.fromStream(dataStream)
      }

      data.get
    }
  }

  override def delete(location: String): Future[Unit] = ???
  override def create(locationPrefix: String, inputStream: InputStream, nBytes: Long): Future[String] = ???
}

object PageByteAStrategy extends PageByteAStrategy {
}
