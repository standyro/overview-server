package com.overviewdocs.test

import java.sql.Connection
import org.postgresql.PGConnection
import org.specs2.execute.AsResult
import org.specs2.mutable.{After,Around}
import org.specs2.specification.{Fragments, Step}
import scala.concurrent.duration.Duration
import scala.concurrent.{Await,Future,blocking}
import slick.jdbc.JdbcBackend.Session

import com.overviewdocs.database.{DB,HasBlockingDatabase}
import com.overviewdocs.test.factories.{DbFactory,PodoFactory}

/**
 * Tests that access the database should extend DbSpecification.
 */
class DbSpecification extends Specification {
  sequential

  protected def await[A](f: Future[A]) = blocking(Await.result(f, Duration.Inf))

  /** Context for test accessing the database.
    *
    * Provides these <em>deprecated</em> variables:
    *
    * <ul>
    *   <li><em>connection</em> (lazy): a Connection
    * </ul>
    *
    * Provides these <em>non-deprecated</em> variables:
    *
    * <ul>
    *   <li><em>database</em>: the Database</li>
    *   <li><em>databaseApi</em>: so you can call <tt>import database.api._</tt>
    *   <li><em>blockingDatabase</em>: the BlockingDatabase</li>
    *   <li><em>sql</em>: runs arbitrary SQL, returning nothing</li>
    *   <li><em>factory</em>: a DbFactory for constructing objects</li>
    *   <li><em>podoFactory</em>: a PodoFactory for constructing objects</li>
    *   <li><em>await</em>: awaits a Future</li>
    * </ul>
    *
    * Whatever code you test with <em>must not commit or start a
    * transaction</em>. When you first use the connection, a transaction will
    * begin; when your test finishes, the transaction will be rolled back.
    */
  trait DbScope extends After with HasBlockingDatabase {
    val connection: Connection = DB.getConnection()
    val pgConnection: PGConnection = connection.unwrap(classOf[PGConnection])
    val factory = DbFactory
    val podoFactory = PodoFactory

    clearDb(connection) // *not* in a before block: that's too late
    override def after = connection.close()

    def sql(q: String): Unit = runQuery(q, connection)
  }

  private def runQuery(query: String, connection: Connection): Unit = {
    val st = connection.createStatement()
    try {
      st.execute(query)
    } finally {
      st.close()
    }
  }

  private def clearDb(connection: Connection) = {
    runQuery("""
      WITH
      q1 AS (DELETE FROM document_store_object),
      q1_i_remember_BASIC_now AS (DELETE FROM dangling_node),
      q2 AS (DELETE FROM store_object),
      q3 AS (DELETE FROM store),
      q4 AS (DELETE FROM temp_document_set_file),
      q5 AS (DELETE FROM node_document),
      q6 AS (DELETE FROM tree),
      q7 AS (DELETE FROM node),
      q8 AS (DELETE FROM document_tag),
      q9 AS (DELETE FROM tag),
      q10 AS (DELETE FROM file),
      q11 AS (DELETE FROM grouped_file_upload),
      q12 AS (DELETE FROM file_group),
      q13 AS (DELETE FROM page),
      q14 AS (DELETE FROM document),
      q15 AS (DELETE FROM uploaded_file),
      q16 AS (DELETE FROM upload),
      q17 AS (DELETE FROM "view"),
      q18 AS (DELETE FROM document_set_user),
      q19 AS (DELETE FROM document_processing_error),
      q20 AS (DELETE FROM document_cloud_import_id_list),
      q21 AS (DELETE FROM document_cloud_import),
      q22 AS (DELETE FROM api_token),
      q23 AS (DELETE FROM plugin),
      q24 AS (DELETE FROM "session"),
      q25 AS (DELETE FROM "user"),
      q26 AS (DELETE FROM csv_import),
      q27 AS (DELETE FROM clone_job),
      q28 AS (DELETE FROM document_set)
      SELECT 1;
    """, connection)
  }
}
