package com.gu.contentapi.circe

import scala.language.experimental.macros
import scala.reflect.macros.blackbox
import com.twitter.scrooge.{ThriftEnum, ThriftStruct, ThriftUnion}
import io.circe.{Decoder, Encoder}
import shapeless.{Lazy, |¬|}

/**
  * Macros for Circe deserialization of various Scrooge-generated classes.
  */
object CirceScroogeMacros {

  /**
    * Macro to provide custom decoding of Thrift structs using the companion object's `apply` method.
    *
    * The macro is needed because Scrooge generates non-sealed traits.
    *
    * Scrooge represents
    *
    * {{{
    * struct Foo {
    *   1: string a
    *   2: i32 b
    * }
    * }}}
    *
    * As a trait plus a companion object with a nested `Immutable` class.
    * Roughly:
    *
    * {{{
    * object Foo {
    *   def apply(a: String, b: Int) = new Immutable(a, b)
    *   class Immutable(val a: String, val b: Int) extends Foo
    * }
    *
    * trait Foo {
    *   def a: String
    *   def b: Int
    * }
    * }}}
    */
  type NotUnion[T] = |¬|[ThriftUnion]#λ[T]  //Tell compiler not to use this Decoder for Unions
  implicit def decodeThriftStruct[A <: ThriftStruct : NotUnion]: Decoder[A] = macro decodeThriftStruct_impl[A]

  def decodeThriftStruct_impl[A: c.WeakTypeTag](c: blackbox.Context)(x: c.Tree): c.Tree = {
    import c.universe._

    val A = weakTypeOf[A]

    val apply = A.companion.member(TermName("apply")) match {
      case symbol if symbol.isMethod && symbol.asMethod.paramLists.size == 1 => symbol.asMethod
      case _ => c.abort(c.enclosingPosition, "Not a valid Scrooge class: could not find the companion object's apply method")
    }

    val params = apply.paramLists.head.zipWithIndex.map { case (param, i) =>
      val name = param.name
      val tpe = param.typeSignature
      val fresh = c.freshName(name)

      val decoderForType = appliedType(weakTypeOf[Decoder[_]].typeConstructor, tpe)
      val implicitDecoder: c.Tree = {
        val normalImplicitDecoder = c.inferImplicitValue(decoderForType)
        if (normalImplicitDecoder.nonEmpty) {
          // Found an implicit, no need to use Lazy.
          // We want to avoid Lazy as much as possible, because extracting its `.value` incurs a runtime cost.
          normalImplicitDecoder
        } else {
          // If we couldn't find an implicit, try again with shapeless `Lazy`.
          // This is to work around a problem with diverging implicits.
          // If you try to summon an implicit for heavily nested type, e.g. `Decoder[Option[Seq[String]]]` then the compiler sometimes gives up.
          // Wrapping with `Lazy` fixes this issue.
          val lazyDecoderForType = appliedType(weakTypeOf[Lazy[_]].typeConstructor, decoderForType)
          val implicitLazyDecoder = c.inferImplicitValue(lazyDecoderForType)
          if (implicitLazyDecoder.isEmpty) c.abort(c.enclosingPosition, s"Could not find an implicit Decoder[$tpe] even after resorting to Lazy")

          // Note: In theory we could use the `implicitLazyDecoder` that we just found, but... for some reason it crashes the compiler :(
          q"_root_.scala.Predef.implicitly[_root_.shapeless.Lazy[_root_.io.circe.Decoder[$tpe]]].value"
        }
      }

      // Note: we don't simply call `cursor.get[$tpe](...)` because we want to avoid allocating HistoryOp instances.
      // See https://github.com/travisbrown/circe/issues/329 for details.
      val decodeParam =
        q"""cursor.cursor.downField(${name.toString})
           .fold[_root_.io.circe.Decoder.Result[$tpe]](_root_.scala.Left(_root_.io.circe.DecodingFailure("Missing field: " + ${name.toString}, Nil)))(x => x.as[$tpe]($implicitDecoder))"""

      val expr =
        if (param.asTerm.isParamWithDefault) {
          // Fallback to param's default value if the JSON field is not present (or can't be decoded for some other reason).
          // Note: reverse-engineering the name of the default value because it's not easily available in the reflection API.
          val defaultValue = A.companion.member(TermName("apply$default$" + (i + 1)))
          fq"""$fresh <- $decodeParam.orElse(_root_.scala.Right($defaultValue))"""
        } else
          fq"""$fresh <- $decodeParam"""
      (fresh, expr)
    }

    /*
      Generates code that looks like this:

      val f$macro$123 = { (cursor: HCursor) =>
        for {
          id$macro$456 <- cursor.get[String]("id")
          type$macro$789 <- cursor.get[TagType]("type")
          ...
        } yield Tag.apply(id$macro$456, type$macro$789)
      }

      Decoder.instance(apply$macro$123)

     */
    val tree = q"""{
      _root_.io.circe.Decoder.instance((cursor: _root_.io.circe.HCursor) => for (..${params.map(_._2)}) yield $apply(..${params.map(_._1)}))
    }"""
    //println(showCode(tree))
    tree
  }

  /**
    * Macro to provide custom decoding of Thrift enums
    */
  implicit def decodeThriftEnum[A <: ThriftEnum]: Decoder[A] = macro decodeThriftEnum_impl[A]

  def decodeThriftEnum_impl[A: c.WeakTypeTag](c: blackbox.Context): c.Tree = {
    import c.universe._

    val A = weakTypeOf[A]
    val typeName = A.typeSymbol.name.toString
    val valueOf = A.companion.member(TermName("valueOf"))
    val unknown = A.companion.member(TermName(s"EnumUnknown$typeName"))

    q"""
    _root_.io.circe.Decoder[String].map(value => {
      val withoutHyphens = _root_.org.apache.commons.lang3.StringUtils.remove(value, '-')
      $valueOf(withoutHyphens).getOrElse($unknown.apply(-1))
    })
    """
  }

  /**
    * Macro to produce Decoders for ThriftUnion types.
    *
    * A ThriftUnion, U, has a companion object which defines a case class extending U for each member of the union.
    * Each case class takes a single parameter, whose type is an alias for the actual type contained by that member.
    *
    * E.g. for the following thrift definition:
    * {{{
    *   union U {
    *     1: FooStruct foo
    *     2: BarStruct bar
    *   }
    *
    *   struct T {
    *     1: U union
    *   }
    * }}}
    *
    * We may expect the following JSON:
    * {{{
    *   {
    *     union: {
    *       foo: {
    *         ...
    *       }
    *     }
    *   }
    * }}}
    *
    * In the scrooge-generated code, for the member foo of union U, there exists a case class:
    *   {{{
    *   case class Foo(foo: FooAlias) extends U
    *   }}}
    * where FooAlias aliases FooStruct.
    * We need to match on the single JSON field and determine which member of the union is present.
    * So the decoder will contain a case statement for the member foo as follows:
    *   {{{
    *   case "foo" => c.cursor.downField("foo").flatMap(_.as[FooStruct](decoderForFooStruct).toOption).map(Foo)
    *   }}}
    *
    */
  implicit def decodeThriftUnion[A <: ThriftUnion]: Decoder[A] = macro decodeThriftUnion_impl[A]

  def decodeThriftUnion_impl[A: c.WeakTypeTag](c: blackbox.Context): c.Tree = {
    import c.universe._

    val A = weakTypeOf[A]

    val memberClasses: Iterable[Symbol] = A.companion.members.filter { member =>
      if (member.isClass && member.name.toString != "UnknownUnionField") {
        member.asClass.baseClasses.contains(A.typeSymbol)
      } else false
    }

    val decoderCases: Iterable[Tree] = memberClasses.map { memberClass =>
      val applyMethod = memberClass.typeSignature.companion.member(TermName("apply")) match {
        case symbol if symbol.isMethod && symbol.asMethod.paramLists.size == 1 => symbol.asMethod
        case _ => c.abort(c.enclosingPosition, s"Not a valid union class: could not find the member class' apply method (${A.typeSymbol.fullName})")
      }

      val param: Symbol = applyMethod.paramLists.headOption.flatMap(_.headOption).getOrElse(c.abort(c.enclosingPosition, s"Not a valid union class: could not find the member class' parameter (${A.typeSymbol.fullName})"))
      val paramType = param.typeSignature.dealias
      val paramName = param.name.toString

      val decoderForParamType = appliedType(weakTypeOf[Decoder[_]].typeConstructor, paramType)
      val implicitDecoderForParam: c.Tree = {
        val normalImplicitDecoder = c.inferImplicitValue(decoderForParamType)
        if (normalImplicitDecoder.nonEmpty) {
          normalImplicitDecoder
        } else {
          val lazyDecoderForType = appliedType(weakTypeOf[Lazy[_]].typeConstructor, decoderForParamType)
          val implicitLazyDecoder = c.inferImplicitValue(lazyDecoderForType)
          if (implicitLazyDecoder.isEmpty) c.abort(c.enclosingPosition, s"Could not find an implicit Decoder[$paramType] even after resorting to Lazy")
          q"_root_.scala.Predef.implicitly[_root_.shapeless.Lazy[_root_.io.circe.Decoder[$paramType]]].value"
        }
      }

      cq"""$paramName => c.cursor.downField($paramName).flatMap(_.as[$paramType]($implicitDecoderForParam).toOption).map($applyMethod)"""
    }

    q"""
      _root_.io.circe.Decoder.instance {(c: _root_.io.circe.HCursor) =>
        val result = c.cursor.fields.getOrElse(Nil).headOption.flatMap {
          case ..${decoderCases ++ Seq(cq"""_ => _root_.scala.None""")}
        }
        Either.fromOption(result, DecodingFailure(${A.typeSymbol.fullName}, c.history))
      }
    """
  }


  implicit def encodeThriftStruct[A <: ThriftStruct : NotUnion]: Encoder[A] = macro encodeThriftStruct_impl[A]

  def encodeThriftStruct_impl[A: c.WeakTypeTag](c: blackbox.Context)(x: c.Tree): c.Tree = {
    import c.universe._

    val A = weakTypeOf[A]
    val apply = A.companion.member(TermName("apply")) match {
      case symbol if symbol.isMethod && symbol.asMethod.paramLists.size == 1 => symbol.asMethod
      case _ => c.abort(c.enclosingPosition, "Not a valid Scrooge class: could not find the companion object's apply method")
    }

    val pairs = apply.paramLists.head.map { param =>
      val name = param.name
      /**
        * We need to ignore any optional fields which are None, because they'll be included in the result as JNulls.
        */
      val (tpe, isOption) = param.typeSignature match {
        case TypeRef(_, sym, ps) if sym == typeOf[Option[_]].typeSymbol => (ps.head, true)
        case other => (other, false)
      }

      val encoderForType = appliedType(weakTypeOf[Encoder[_]].typeConstructor, tpe)
      val implicitEncoder: c.Tree = {
        val normalImplicitEncoder = c.inferImplicitValue(encoderForType)
        if (normalImplicitEncoder.nonEmpty) {
          // Found an implicit, no need to use Lazy.
          // We want to avoid Lazy as much as possible, because extracting its `.value` incurs a runtime cost.
          normalImplicitEncoder
        } else {
          // If we couldn't find an implicit, try again with shapeless `Lazy`.
          // This is to work around a problem with diverging implicits.
          // If you try to summon an implicit for heavily nested type, e.g. `Encoder[Option[Seq[String]]]` then the compiler sometimes gives up.
          // Wrapping with `Lazy` fixes this issue.
          val lazyEncoderForType = appliedType(weakTypeOf[Lazy[_]].typeConstructor, encoderForType)
          val implicitLazyEncoder = c.inferImplicitValue(lazyEncoderForType)
          if (implicitLazyEncoder.isEmpty) c.abort(c.enclosingPosition, s"Could not find an implicit Encoder[$tpe] even after resorting to Lazy")

          // Note: In theory we could use the `implicitLazyEncoder` that we just found, but... for some reason it crashes the compiler :(
          q"_root_.scala.Predef.implicitly[_root_.shapeless.Lazy[_root_.io.circe.Encoder[$tpe]]].value"
        }
      }

      if (isOption) q"""thrift.${name.toTermName}.map(${name.toString} -> $implicitEncoder.apply(_))"""
      else q"""_root_.scala.Some(${name.toString} -> $implicitEncoder.apply(thrift.${name.toTermName}))"""
    }

    val tree =
      q"""{ _root_.io.circe.Encoder.instance((thrift: $A) => _root_.io.circe.Json.fromFields($pairs.flatten)) }"""

    tree

  }

  implicit def encodeThriftUnion[A <: ThriftUnion]: Encoder[A] = macro encodeThriftUnion_impl[A]

  def encodeThriftUnion_impl[A: c.WeakTypeTag](c: blackbox.Context): c.Tree = {
    import c.universe._

    val A = weakTypeOf[A]

    val memberClasses: Iterable[Symbol] = A.companion.members.filter { member =>
      if (member.isClass && member.name.toString != "UnknownUnionField") {
        member.asClass.baseClasses.contains(A.typeSymbol)
      } else false
    }

    val encoderCases: Iterable[Tree] = memberClasses.map { memberClass =>
      val applyMethod = memberClass.typeSignature.companion.member(TermName("apply")) match {
        case symbol if symbol.isMethod && symbol.asMethod.paramLists.size == 1 => symbol.asMethod
        case _ => c.abort(c.enclosingPosition, s"Not a valid union class: could not find the member class' apply method (${A.typeSymbol.fullName})")
      }

      val param: Symbol = applyMethod.paramLists.headOption.flatMap(_.headOption).getOrElse(c.abort(c.enclosingPosition, s"Not a valid union class: could not find the member class' parameter (${A.typeSymbol.fullName})"))
      val paramType = param.typeSignature.dealias

      val encoderForParamType = appliedType(weakTypeOf[Encoder[_]].typeConstructor, paramType)
      val implicitEncoderForParam: c.Tree = {
        val normalImplicitEncoder = c.inferImplicitValue(encoderForParamType)
        if (normalImplicitEncoder.nonEmpty) {
          normalImplicitEncoder
        } else {
          val lazyEncoderForType = appliedType(weakTypeOf[Lazy[_]].typeConstructor, encoderForParamType)
          val implicitLazyEncoder = c.inferImplicitValue(lazyEncoderForType)
          if (implicitLazyEncoder.isEmpty) c.abort(c.enclosingPosition, s"Could not find an implicit Encoder[$paramType] even after resorting to Lazy")
          q"_root_.scala.Predef.implicitly[_root_.shapeless.Lazy[_root_.io.circe.Encoder[$paramType]]].value"
        }
      }

      cq"""data: $memberClass => Json.obj(${param.name.toString} -> $implicitEncoderForParam.apply(data.${param.name.toTermName}))"""
    }

    q"""
      _root_.io.circe.Encoder.instance {
        case ..${encoderCases ++ Seq(cq"""_ => _root_.io.circe.Json.Null""")}
      }
    """
  }
}
