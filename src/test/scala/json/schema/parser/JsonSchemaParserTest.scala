package json.schema.parser

import java.io.{File, FilenameFilter}
import java.net.URI

import org.scalacheck.Gen
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Inspectors, FlatSpec, Matchers}

import scalaz.{Failure, Success, Validation}

trait ScalazMatchers {

  def containFailure(contain: String) =
    new Matcher[Validation[_, _]] {
      def apply(left: Validation[_, _]) = {
        val r = left match {
          case Failure(e) => e.toString.contains(contain)
          case _ => false
        }
        MatchResult(r, s"$left does not contain '$contain'", s"$left contains '$contain'")
      }
    }

}

class JsonSchemaParserTest extends FlatSpec with Inspectors with Matchers with ScalazMatchers {


  def parse(s: String) = JsonSchemaParser.parse(s).validation

  JsonSchemaParser.getClass.toString should "parse empty schemas" in {

    parse(
      """
        |{}
      """.stripMargin) shouldBe a[Success[_]]

  }

  it should "parse schemas with optional valid fields" in {

    parse(
      """
        |{
        |"title":"root"
        |}
      """.stripMargin).map { d: SchemaDocument[Double] => d.title} shouldBe Success(Some("root"))

    parse(
      """
        |{
        |"title":10
        |}
      """.stripMargin).map { d: SchemaDocument[Double] => d.title} shouldBe Failure("String: [--\\(title)]")

  }

  it should "parse nested schemas" in {

    val result = parse(
      """
        |{
        |    "title": "root",
        |    "otherSchema": {
        |        "title": "nested",
        |        "anotherSchema": {
        |            "title": "alsoNested"
        |        }
        |    }
        |}
      """.stripMargin)

    result.map(_.title) shouldBe Success(Some("root"))
    result.map(_.nestedSchemas("otherSchema").title) shouldBe Success(Some("nested"))
    result.map(_.nestedSchemas("otherSchema").nestedSchemas("anotherSchema").title) shouldBe Success(Some("alsoNested"))


  }



  it should "parse and validate $schemas" in {

    parse(
      """
        |{
        |    "$schema":"http://json-schema.org/schema#"
        |}
      """.stripMargin).map(_.schema) shouldBe Success(Some(new URI("http://json-schema.org/schema#")))

    parse(
      """
        |{
        |    "$schema":"http://json-schema.org/draft-04/schema#"
        |}
      """.stripMargin).map(_.schema) shouldBe Success(Some(new URI("http://json-schema.org/draft-04/schema#")))

    parse(
      """
        |{
        |    "$schema":"http://json-schema.org/draft-03/schema#"
        |}
      """.stripMargin).map(_.schema) should containFailure("not supported schema")

  }


  it should "validate id" in {

    parse(
      """
        |{
        |    "id":"http://x.y.z/rootschema.json#"
        |}
      """.stripMargin).map(_.scope) shouldBe Success(new URI("http://x.y.z/rootschema.json#"))

    parse(
      """
        |{
        |    "id":"#nested"
        |}
      """.stripMargin).map(_.scope) shouldBe Success(new URI("#nested"))

    parse(
      """
        |{
        |    "id":""
        |}
      """.stripMargin).map(_.schema) shouldBe Success(None)
  }

  it should "resolve id based on parent schema" in {

    val r = parse(
      """
        |{
        |    "id": "http://x.y.z/rootschema.json#",
        |    "schema1": {
        |        "id": "#foo"
        |    },
        |    "schema2": {
        |        "id": "otherschema.json",
        |        "nested": {
        |            "id": "#bar"
        |        },
        |        "alsonested": {
        |            "id": "t/inner.json#a"
        |        }
        |    },
        |    "schema3": {
        |        "id": "some://where.else/completely#"
        |    }
        |}
      """.stripMargin)

    r.map(_.scope) shouldBe Success(new URI("http://x.y.z/rootschema.json#"))
    r.map(_.nestedSchemas("schema1").scope) shouldBe Success(new URI("http://x.y.z/rootschema.json#foo"))
    r.map(_.nestedSchemas("schema2").scope) shouldBe Success(new URI("http://x.y.z/otherschema.json#"))
    r.map(_.nestedSchemas("schema2").nestedSchemas("nested").scope) shouldBe Success(new URI("http://x.y.z/otherschema.json#bar"))
    r.map(_.nestedSchemas("schema2").nestedSchemas("alsonested").scope) shouldBe Success(new URI("http://x.y.z/t/inner.json#a"))
    r.map(_.nestedSchemas("schema3").scope) shouldBe Success(new URI("some://where.else/completely#"))
  }

  it should "resolve to base schema uri if no id in scope" in {

    val r = parse(
      """
        |{
        |    "id": "http://x.y.z/rootschema.json#",
        |    "schema4": {
        |
        |    }
        |}
      """.stripMargin)

    r.map(_.scope) shouldBe Success(new URI("http://x.y.z/rootschema.json#"))
    r.map(_.nestedSchemas("schema4").scope) shouldBe Success(new URI("http://x.y.z/rootschema.json#"))
  }


  it should "resolve agaist empty location if no id" in {

    val r = parse(
      """
        |{
        |    "$schema":"http://json-schema.org/draft-04/schema#",
        |    "schema4": {
        |            "id": "#bar"
        |    }
        |}
      """.stripMargin)

    r.map(_.scope) shouldBe Success(new URI("#"))
    r.map(_.nestedSchemas("schema4").scope) shouldBe Success(new URI("#bar"))
  }

  it should "decodes schema references" in {

    val r = parse(
      """
        |{
        |    "id": "http://my.site/myschema#",
        |    "definitions": {
        |        "schema1": {
        |            "id": "schema1",
        |            "type": "integer"
        |        },
        |        "schema2": {
        |            "type": "array",
        |            "items": { "$ref": "schema1" }
        |        }
        |    }
        |}
        | """.stripMargin)

    r.map(_.scope) shouldBe Success(new URI("http://my.site/myschema#"))
    r.map(_.definitions("schema1").scope) shouldBe Success(new URI("http://my.site/schema1#"))
    r.map(_.definitions("schema2").array.get.items.value.head.scope) shouldBe Success(new URI("http://my.site/schema1#"))

  }

  it should "decodes schema references to the same instance" in {
    val r = parse(
      """
        |{
        |    "id": "http://my.site/myschema#",
        |    "definitions": {
        |        "schema1": {
        |            "type": "integer"
        |        },
        |        "schema2": {
        |            "type": "array",
        |            "items": { "$ref": "#/definitions/schema1" }
        |        }
        |    }
        |}
        | """.stripMargin)

    r.map(_.scope) shouldBe Success(new URI("http://my.site/myschema#"))
    r.map(_.definitions("schema2").array.get.items.value.head.types.head) shouldBe Success(SimpleType.integer)

  }

  it should "decodes pointer references to overriden scope" in {
    val r =     parse(
      """
        |{
        | "id": "product",
        |"type":"object",
        |"properties": {
        |"a":{"$ref": "#/definitions/overriden"}
        |},
        |"definitions":{
        | "typea":{
        | "id":"#/definitions/overriden",
        | "type":"string"
        | }
        |}
        |}
      """.stripMargin)

    r.map(_.scope) shouldBe Success(new URI("product#"))
    r.map(_.obj.get.properties.value("a").schema.types) shouldBe Success(Set(SimpleType.string))
    r.map(_.obj.get.properties.value("a").schema.id) shouldBe Success(Some(new URI("product#/definitions/overriden")))

  }

  it should "decodes pointer references and preserves the reference as id" in {
    val r =     parse(
      """
        |{
        | "id": "product",
        |"type":"object",
        |"properties": {
        |"a":{"$ref": "#/definitions/typea"},
        |"b":{"$ref": "http://json-schema.org/address#"}
        |},
        |"definitions":{
        | "typea":{
        | "type":"string"
        | }
        |}
        |}
      """.stripMargin)

    r.map(_.scope) shouldBe Success(new URI("product#"))
    r.map(_.obj.get.properties.value("a").schema.types) shouldBe Success(Set(SimpleType.string))
    r.map(_.obj.get.properties.value("a").schema.id) shouldBe Success(Some(new URI("product#/definitions/typea")))
    r.map(_.obj.get.properties.value("b").schema.id) shouldBe Success(Some(new URI("http://json-schema.org/address#")))

  }

  implicit val remoteCyclicSchemas: List[URI] = List(new URI("http://swagger.io/v2/schema.json"))

  implicit val validSchemas: List[File] = new File("src/test/resources/json/schema/parser/valid").listFiles(new FilenameFilter {
    override def accept(dir: File, name: String): Boolean = name endsWith ".json"
  }).toList

  implicit val cyclicSchemas: List[File] = new File("src/test/resources/json/schema/parser/recursive").listFiles(new FilenameFilter {
    override def accept(dir: File, name: String): Boolean = name endsWith ".json"
  }).toList

  it should "parse all valid schemas" in {
    forAll (validSchemas) { f => JsonSchemaParser.parse(f).validation.isSuccess shouldBe true }
  }


  it should "parse remote schemas with cyclic reference" in {
    forAll (remoteCyclicSchemas) { f => JsonSchemaParser.parse(f).validation.isSuccess shouldBe true }
  }


  it should "parse schemas with cyclic reference" in {
    forAll (cyclicSchemas) { f => JsonSchemaParser.parse(f).validation.isSuccess shouldBe true }
  }
}
