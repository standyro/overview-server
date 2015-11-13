package com.overviewdocs.util

import org.specs2.mutable.Specification
import org.specs2.mock.Mockito
import org.specs2.specification.Scope

import com.overviewdocs.csv.CsvImportDocumentProducer
import com.overviewdocs.http.DocumentCloudDocumentProducer
import com.overviewdocs.models.{DocumentSetCreationJob,DocumentSetCreationJobType}

class DocumentProducerFactorySpec extends Specification with Mockito {
  "DocumentProducerFactory" should {
    trait BaseScope extends Scope {
      val factory = com.overviewdocs.test.factories.PodoFactory

      val documentSetCreationJob = smartMock[DocumentSetCreationJob]
      documentSetCreationJob.documentcloudUsername returns None
      documentSetCreationJob.documentcloudPassword returns None
    }

    "create a DocumentCloudDocumentProducer" in new BaseScope {
      documentSetCreationJob.jobType returns DocumentSetCreationJobType.DocumentCloud
      documentSetCreationJob.contentsOid returns None
      val documentSet = factory.documentSet(title="title", query=Some("query"))
      val producer: DocumentProducer = DocumentProducerFactory.create(documentSetCreationJob, documentSet, { _ => true })

      producer match {
        case p: DocumentCloudDocumentProducer => success
        case _ => failure
      }
    }

    "create a CsvImportDocumentProducer" in new BaseScope {
      documentSetCreationJob.jobType returns DocumentSetCreationJobType.CsvUpload
      documentSetCreationJob.contentsOid returns Some(0l)
      val documentSet = factory.documentSet(title="title", uploadedFileId = Some(100L))
      val producer: DocumentProducer = DocumentProducerFactory.create(documentSetCreationJob, documentSet, {_ => true })

      producer match {
        case p: CsvImportDocumentProducer => success
        case _ => failure
      }
    }
  }
}
