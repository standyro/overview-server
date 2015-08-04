package com.overviewdocs.jobhandler.filegroup.task.process

import scala.concurrent.ExecutionContext
import com.overviewdocs.jobhandler.filegroup.task.step.CreateFileWithView
import com.overviewdocs.jobhandler.filegroup.task.step.TaskStep
import com.overviewdocs.models.File
import com.overviewdocs.models.GroupedFileUpload
import com.overviewdocs.jobhandler.filegroup.task.TimeoutGenerator

object DoCreateFileWithView {

  def apply(documentSetId: Long, timeoutGenerator: TimeoutGenerator)(implicit executor: ExecutionContext) = new StepGenerator[GroupedFileUpload, File] {
    
    override def generate(uploadedFile: GroupedFileUpload): TaskStep = 
      CreateFileWithView(documentSetId, uploadedFile, timeoutGenerator, nextStepFn)
  }
}