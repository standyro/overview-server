package controllers

import collection.JavaConversions._
import play.api.data._
import play.api.data.Forms._
import play.api.mvc.Action
import play.api.db.DB
import play.api.Play.current
import org.squeryl.PrimitiveTypeMode.inTransaction
import models.DocumentSet
import models.orm.DocumentSetCreationJob
import models.orm.Schema

object DocumentSetController extends Base {
  val queryForm = Form(
    mapping(
      "query" -> text
    ) ((query) => new DocumentSetCreationJob(query))
      ((job: DocumentSetCreationJob) => Some((job.query)))
  ) 

  def index() = authorizedAction(anyUser) { user => request =>
    DB.withTransaction { connection =>
      inTransaction { // FIXME remove!
        val documentSets = user.documentSets.page(0, 20).toSeq
        val documentSetCreationJobs = user.documentSetCreationJobs.toSeq
        Ok(views.html.DocumentSet.index(documentSets, documentSetCreationJobs, queryForm))
     }
    }
  }

  def show(documentSetId: Long) = authorizedAction(anyUser) { user => request =>
    // FIXME check user has access to document set
    val documentSet = DocumentSet.find.byId(documentSetId) // for potential 404 error
    Ok(views.html.DocumentSet.show())
  }

  def create() = authorizedAction(anyUser) { user => implicit request =>
    val documentSetCreationJob: DocumentSetCreationJob = queryForm.bindFromRequest.get
    inTransaction { 
      user.documentSetCreationJobs.associate(documentSetCreationJob)
    }
    

    Redirect(routes.DocumentSetController.index())
  }
}
