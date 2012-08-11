/*
 * NodeWriterSpec.scala
 * 
 * Overview Project
 * Created by Jonas Karlsson, Aug 2012
 */

package writers

import anorm._
import anorm.SqlParser._
import clustering.DocTreeNode
import clustering.ClusterTypes.DocumentID
import helpers.DbTestContext
import java.sql.Connection
import org.specs2.mutable.Specification
import scala.collection.mutable.Set
import org.specs2.matcher.HaveTheSameElementsAs



class NodeWriterSpec extends Specification {
  

  trait DocumentSetSetup extends DbTestContext {
    def insertDocumentSet(query: String): Long = {
      SQL("""
          INSERT INTO document_set(id, query) 
          VALUES(nextval('document_set_seq'), 'NodeWriterSpec')
          """).executeInsert().getOrElse(throw new Exception("failed insert"))
    }

    lazy val documentSetId = insertDocumentSet("NodeWriterSpec")
  }
  
  private def addChildren(parent: DocTreeNode, description: String) : Seq[DocTreeNode] = {
    val children = for (i <- 1 to 2) yield new DocTreeNode(Set())
    children.foreach(_.description = description)
    children.foreach(parent.children.add)
    
    children
  }
  
  private val nodeDataParser = long("id") ~ str("description") ~ 
		  					   get[Option[Long]]("parent_id") ~ long("document_set_id")
		  					   
  "NodeWriter" should {
    
    "insert root node with description, document set, and no parent" in new DocumentSetSetup {
          
      val root = new DocTreeNode(Set())
      val description = "description"
      root.description = description
      
      val writer = new NodeWriter(documentSetId)
      
      writer.write(root)
      
      val result = 
        SQL("SELECT id, description, parent_id, document_set_id FROM node").
      as(nodeDataParser map(flatten) singleOpt)
                      
      result must beSome
      val (id, rootDescription, parentId, rootDocumentSetId) = result.get
      
      rootDescription must be equalTo(description)
      parentId must beNone
      rootDocumentSetId must be equalTo(documentSetId)
    }
    
    "insert child nodes" in new DocumentSetSetup {
      val root = new DocTreeNode(Set())
      root.description = "root"
      val childNodes = addChildren(root, "child")
      val grandChildNodes = childNodes.map(n => (n, addChildren(n, "grandchild")))
      val writer = new NodeWriter(documentSetId)
      
      writer.write(root)
      
      val savedRoot = SQL("""
    		  		   SELECT id, description, parent_id, document_set_id FROM node
                       WHERE description = 'root'
    		  		   """).as(nodeDataParser map(flatten) singleOpt)
        
      savedRoot must beSome
      val (rootId, _, _, _) = savedRoot.get
      
      val savedChildren = 
        SQL("""
    	    SELECT id, description, parent_id, document_set_id FROM node
            WHERE parent_id = {rootId} AND description = 'child'
    		""").on("rootId" -> rootId).as(nodeDataParser map(flatten) *)
 
      val childIds = savedChildren.map(_._1)
      childIds must have size(2)
      
      val savedGrandChildren =
        SQL("""
    	    SELECT id, description, parent_id, document_set_id FROM node
            WHERE parent_id IN """ + childIds.mkString("(", ",", ")") + """ 
            AND description = 'grandchild'
    		""").on("rootId" -> rootId).as(nodeDataParser map(flatten) *)
      
      savedGrandChildren must have size(4)
    }
  }
}
