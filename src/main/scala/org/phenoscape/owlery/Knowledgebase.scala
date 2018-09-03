package org.phenoscape.owlery

import org.apache.jena.query.{Query, ResultSet}
import org.phenoscape.owlet.Owlet
import org.semanticweb.owlapi.apibinding.OWLManager
import org.semanticweb.owlapi.model.{OWLClassExpression, OWLEntity, OWLNamedIndividual, OWLObject}
import org.semanticweb.owlapi.reasoner.OWLReasoner
import org.semanticweb.owlapi.search.EntitySearcher
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

case class Knowledgebase(name: String, reasoner: OWLReasoner) {

  private val factory = OWLManager.getOWLDataFactory
  private lazy val owlet = new Owlet(this.reasoner)
  private val jsonldContext = Map("@context" -> "https://owlery.phenoscape.org/json/context.jsonld").toJson

  def performSPARQLQuery(query: Query): Future[ResultSet] = Future { owlet.performSPARQLQuery(query) }

  def expandSPARQLQuery(query: Query): Future[Query] = Future { owlet.expandQuery(query) }

  def querySuperClasses(expression: OWLClassExpression, direct: Boolean, includeEquivalent: Boolean, includeThing: Boolean, includeDeprecated: Boolean): Future[JsObject] = Future {
    val superClasses = Map("subClassOf" -> reasoner.getSuperClasses(expression, direct).getFlattened.asScala
      .filterNot(!includeThing && _.isOWLThing)
      .filterNot(!includeDeprecated && isDeprecated(_))
      .map(_.getIRI.toString).toList)
    val json = merge(toQueryObject(expression), superClasses.toJson, jsonldContext)
    if (includeEquivalent) {
      val equivalents = Map("equivalentClass" -> reasoner.getEquivalentClasses(expression).getEntities.asScala
        .filterNot(_ == expression)
        .filterNot(!includeDeprecated && isDeprecated(_))
        .map(_.getIRI.toString).toList)
      merge(json, equivalents.toJson)
    } else json
  }

  def querySubClasses(expression: OWLClassExpression, direct: Boolean, includeEquivalent: Boolean, includeNothing: Boolean, includeDeprecated: Boolean): Future[JsObject] = Future {
    val subClasses = Map("superClassOf" -> reasoner.getSubClasses(expression, direct).getFlattened.asScala
      .filterNot(!includeNothing && _.isOWLNothing)
      .filterNot(!includeDeprecated && isDeprecated(_))
      .map(_.getIRI.toString).toList)
    val json = merge(toQueryObject(expression), subClasses.toJson, jsonldContext)
    if (includeEquivalent) {
      val equivalents = Map("equivalentClass" -> reasoner.getEquivalentClasses(expression).getEntities.asScala
        .filterNot(_ == expression)
        .filterNot(!includeDeprecated && isDeprecated(_))
        .map(_.getIRI.toString).toList)
      merge(json, equivalents.toJson)
    } else json
  }

  def queryInstances(expression: OWLClassExpression, direct: Boolean, includeDeprecated: Boolean): Future[JsObject] = Future {
    val results = Map("hasInstance" -> reasoner.getInstances(expression, direct).getFlattened.asScala
      .filterNot(!includeDeprecated && isDeprecated(_))
      .map(_.getIRI.toString).toList)
    merge(toQueryObject(expression), results.toJson, jsonldContext)
  }

  def queryEquivalentClasses(expression: OWLClassExpression, includeDeprecated: Boolean): Future[JsObject] = Future {
    val results = Map("equivalentClass" -> reasoner.getEquivalentClasses(expression).getEntities.asScala
      .filterNot(_ == expression)
      .filterNot(!includeDeprecated && isDeprecated(_))
      .map(_.getIRI.toString).toList)
    merge(toQueryObject(expression), results.toJson, jsonldContext)
  }

  def isSatisfiable(expression: OWLClassExpression): Future[JsObject] = Future {
    val results = Map("isSatisfiable" -> reasoner.isSatisfiable(expression))
    merge(toQueryObject(expression), results.toJson, jsonldContext)
  }

  def queryTypes(individual: OWLNamedIndividual, direct: Boolean, includeThing: Boolean, includeDeprecated: Boolean): Future[JsObject] = Future {
    val results = Map("type" -> reasoner.getTypes(individual, direct).getFlattened.asScala
      .filterNot(!includeThing && _.isOWLThing)
      .filterNot(!includeDeprecated && isDeprecated(_))
      .map(_.getIRI.toString).toList)
    merge(toQueryObject(individual), results.toJson, jsonldContext)
  }

  lazy val summary: Future[JsObject] = Future {
    val summaryObj = Map(
      "label" -> name.toJson,
      //"reasoner" -> reasoner.getReasonerName.toJson, //FIXME currently HermiT returns null
      "isConsistent" -> reasoner.isConsistent.toJson,
      "logicalAxiomsCount" -> reasoner.getRootOntology.getLogicalAxiomCount.toJson)
    merge(summaryObj.toJson, jsonldContext)
  }

  private def toQueryObject(expression: OWLObject): JsObject = expression match {
    case named: OWLEntity => JsObject("@id" -> named.getIRI.toString.toJson)
    case anonymous => JsObject(
      "@id" -> "_:b0".toJson,
      "value" -> anonymous.toString.toJson) //TODO do a better job of converting the expression to a string
  }

  private def merge(jsonObjects: JsValue*): JsObject = {
    JsObject(jsonObjects.flatMap(_.asInstanceOf[JsObject].fields).toMap) //TODO do this without casting
  }

  private def isDeprecated(entity: OWLEntity): Boolean =
    reasoner.getRootOntology.getImportsClosure.asScala.exists { o =>
      EntitySearcher.getAnnotations(entity, o, factory.getOWLDeprecated).asScala.exists { v =>
        Option(v.getValue.asLiteral.orNull).exists(l => l.isBoolean && l.parseBoolean)
      }
    }

}