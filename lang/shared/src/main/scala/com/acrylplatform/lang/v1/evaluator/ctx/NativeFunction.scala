package com.acrylplatform.lang.v1.evaluator.ctx

import cats.Eval
import cats.data.EitherT
import com.acrylplatform.lang.directives.DirectiveDictionary
import com.acrylplatform.lang.directives.values.StdLibVersion
import com.acrylplatform.lang.TrampolinedExecResult
import com.acrylplatform.lang.v1.FunctionHeader
import com.acrylplatform.lang.v1.compiler.Terms.{EVALUATED, EXPR}
import com.acrylplatform.lang.v1.compiler.Types._

import scala.annotation.meta.field
import scala.scalajs.js.annotation._

sealed trait BaseFunction {
  @JSExport def signature: FunctionTypeSignature
  @JSExport def header: FunctionHeader = signature.header
  def costByLibVersion: Map[StdLibVersion, Long]
  @JSExport def name: String
  @JSExport def docString: String
  @JSExport def argsDoc: Array[(String, String)]
  @JSExport def deprecated: Boolean = false
}

object BaseFunction {
  implicit def header(bf: BaseFunction): FunctionHeader = bf.header
}

@JSExportTopLevel("FunctionTypeSignature")
case class FunctionTypeSignature(result: TYPE, args: Seq[(String, TYPE)], header: FunctionHeader)

@JSExportTopLevel("NativeFunction")
case class NativeFunction(@(JSExport @field) name: String,
                          costByLibVersion: Map[StdLibVersion, Long],
                          @(JSExport @field) signature: FunctionTypeSignature,
                          ev: List[EVALUATED] => Either[String, EVALUATED],
                          @(JSExport @field) docString: String,
                          @(JSExport @field) argsDoc: Array[(String, String)])
    extends BaseFunction {
  def eval(args: List[EVALUATED]): TrampolinedExecResult[EVALUATED] = EitherT.fromEither[Eval](ev(args))
}

object NativeFunction {

  def apply(name: String, cost: Long, internalName: Short, resultType: TYPE, docString: String, args: (String, TYPE, String)*)(
      ev: PartialFunction[List[EVALUATED], Either[String, EVALUATED]]) =
    new NativeFunction(
      name = name,
      costByLibVersion = DirectiveDictionary[StdLibVersion].all.map(_ -> cost).toMap,
      signature = FunctionTypeSignature(result = resultType, args = args.map(a => (a._1, a._2)), header = FunctionHeader.Native(internalName)),
      ev = ev.orElse{ case _ => Left("Passed argument with wrong type").asInstanceOf[Either[String, EVALUATED]]},
      docString = docString,
      argsDoc = args.map(a => (a._1 -> a._3)).toArray
    )

  def apply(name: String,
            costByLibVersion: Map[StdLibVersion, Long],
            internalName: Short,
            resultType: TYPE,
            docString: String,
            args: (String, TYPE, String)*)(ev: List[EVALUATED] => Either[String, EVALUATED]) =
    new NativeFunction(
      name = name,
      costByLibVersion = costByLibVersion,
      signature = FunctionTypeSignature(result = resultType, args = args.map(a => (a._1, a._2)), header = FunctionHeader.Native(internalName)),
      ev = ev,
      docString = docString,
      argsDoc = args.map(a => (a._1 -> a._3)).toArray
    )

}

@JSExportTopLevel("UserFunction")
case class UserFunction(@(JSExport @field) name: String,
                        @(JSExport @field) internalName: String,
                        costByLibVersion: Map[StdLibVersion, Long],
                        @(JSExport @field) signature: FunctionTypeSignature,
                        ev: EXPR,
                        @(JSExport @field) docString: String,
                        @(JSExport @field) argsDoc: Array[(String, String)])
    extends BaseFunction

object UserFunction {

  def apply(name: String, cost: Long, resultType: TYPE, docString: String, args: (String, TYPE, String)*)(ev: EXPR): UserFunction =
    UserFunction(name, name, DirectiveDictionary[StdLibVersion].all.map(_ -> cost).toMap, resultType, docString, args: _*)(ev)

  def deprecated(name: String, cost: Long, resultType: TYPE, docString: String, args: (String, TYPE, String)*)(ev: EXPR): UserFunction =
    UserFunction.deprecated(name, name, DirectiveDictionary[StdLibVersion].all.map(_ -> cost).toMap, resultType, docString, args: _*)(ev)

  def apply(name: String, costByLibVersion: Map[StdLibVersion, Long], resultType: TYPE, docString: String, args: (String, TYPE, String)*)(
      ev: EXPR): UserFunction =
    UserFunction(name, name, costByLibVersion, resultType, docString, args: _*)(ev)

  def apply(name: String, internalName: String, cost: Long, resultType: TYPE, docString: String, args: (String, TYPE, String)*)(
      ev: EXPR): UserFunction =
    UserFunction(name, internalName, DirectiveDictionary[StdLibVersion].all.map(_ -> cost).toMap, resultType, docString, args: _*)(ev)

  def apply(name: String,
            internalName: String,
            costByLibVersion: Map[StdLibVersion, Long],
            resultType: TYPE,
            docString: String,
            args: (String, TYPE, String)*)(ev: EXPR): UserFunction =
    new UserFunction(
      name = name,
      internalName = internalName,
      costByLibVersion = costByLibVersion,
      signature = FunctionTypeSignature(result = resultType, args = args.map(a => (a._1, a._2)), header = FunctionHeader.User(internalName)),
      ev = ev,
      docString = docString,
      argsDoc = args.map(a => (a._1 -> a._3)).toArray
    )

  def deprecated(name: String,
                 internalName: String,
                 costByLibVersion: Map[StdLibVersion, Long],
                 resultType: TYPE,
                 docString: String,
                 args: (String, TYPE, String)*)(ev: EXPR): UserFunction =
    new UserFunction(
      name = name,
      internalName = internalName,
      costByLibVersion = costByLibVersion,
      signature = FunctionTypeSignature(result = resultType, args = args.map(a => (a._1, a._2)), header = FunctionHeader.User(internalName)),
      ev = ev,
      docString = docString,
      argsDoc = args.map(a => (a._1 -> a._3)).toArray
    ) { override def deprecated = true }
}
