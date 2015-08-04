package com.overviewdocs.jobhandler.filegroup

import akka.actor.ActorRef
import org.specs2.mock.Mockito
import com.overviewdocs.jobhandler.filegroup.task.UploadProcessOptions

class TestCreateDocumentsJobShepherd(
    val documentSetId: Long,
    val fileGroupId: Long, 
    val options: UploadProcessOptions,
    val taskQueue: ActorRef,
    val progressReporter: ActorRef,
    val documentIdSupplier: ActorRef,
    uploadedFileIds: Set[Long]) extends CreateDocumentsJobShepherd with Mockito {
  override protected val storage = smartMock[Storage]
  
  storage.uploadedFileIds(fileGroupId) returns uploadedFileIds.toSet
}
