package ru.retailrocket.spark

import scala.reflect.ClassTag
import scala.util._

import org.apache.spark.rdd._
import org.apache.spark.sql._
import org.apache.hadoop.io.compress._


package object multitool {
  object Functions {
    def tap[T:ClassTag](f: T => Unit)(o: T) = {f(o); o}
    def applyIf[T:ClassTag](p: Boolean)(f: T => T)(o: T): T = {if(p) f(o) else o}
    def tapIf[T:ClassTag](p: Boolean)(f: T => Unit)(o: T) = {if(p) f(o); o}

    def maxBy[T,O<%Ordered[O]](f:T=>O)(a:T, b:T) = if(f(a)>f(b)) a else b
    def minBy[T,O<%Ordered[O]](f:T=>O)(a:T, b:T) = if(f(a)<f(b)) a else b

    def sumTuple2[T](a: (T,T), b: (T,T))(implicit num: Numeric[T]): (T,T) = {
      import num._
      (a._1+b._1, a._2+b._2)
    }

    def sumTuple3[T](a: (T,T,T), b: (T,T,T))(implicit num: Numeric[T]): (T,T,T) = {
      import num._
      (a._1+b._1, a._2+b._2, a._3+b._3)
    }
  }

  object PairFunctions {
    def flatMapValues[K,V,T](src: Traversable[(K,V)])(f: (V) => TraversableOnce[T]): Traversable[(K,T)] =
      src.flatMap { case (k,v) => f(v).map { r => (k, r) } }

    def mapValues[K,V,T](src: Traversable[(K,V)])(f: (V) => T): Traversable[(K,T)] =
      src.map {case (k,v) => (k, f(v)) }

    def groupByKey[K,V](src: Traversable[(K,V)]) =
      mapValues(src.groupBy { _._1 } ) { _.map { _._2 } }

    def reduceByKey[K,V](src: Traversable[(K,V)])(f: (V,V) => V): Traversable[(K,V)] =
      groupByKey(src).map { case (k, vs) => (k, vs.reduce(f)) }

    def countByKey[K,V](src: Traversable[(K,V)]): Map[K, Long] =
      reduceByKey(src.map { case (k, v) => (k, 1L) } ) { _+_ }.toMap

    def cogroup[K,V1,V2](src1: Traversable[(K,V1)], src2: Traversable[(K,V2)]): Traversable[(K, (Traversable[V1], Traversable[V2]))] = {
      val g1 = groupByKey(src1).toMap
      val g2 = groupByKey(src2).toMap
      val ks = g1.keys.toSet | g2.keys.toSet
      for {
        k <- ks.toSeq
        vs1 = g1.get(k).toList.flatten
        vs2 = g2.get(k).toList.flatten
      } yield (k, (vs1, vs2))
    }

    def join[K,V1,V2](src1: Traversable[(K,V1)], src2: Traversable[(K,V2)]): Traversable[(K, (V1, V2))] = {
      for {
        (k, (vs1, vs2)) <- cogroup(src1, src2)
        v1 <- vs1
        v2 <- vs2
      } yield (k, (v1, v2))
    }

    def leftOuterJoin[K,V1,V2](src1: Traversable[(K,V1)], src2: Traversable[(K,V2)]): Traversable[(K, (V1, Option[V2]))] = {
      for {
        (k, (vs1, vs2)) <- cogroup(src1, src2)
        v1 <- vs1
        v2 <- if(vs2.isEmpty) Seq(None) else vs2.map { Option(_) }
      } yield (k, (v1, v2))
    }

    def rightOuterJoin[K,V1,V2](src1: Traversable[(K,V1)], src2: Traversable[(K,V2)]): Traversable[(K, (Option[V1], V2))] = {
      for {
        (k, (vs1, vs2)) <- cogroup(src1, src2)
        v1 <- if(vs1.isEmpty) Seq(None) else vs1.map { Option(_) }
        v2 <- vs2
      } yield (k, (v1, v2))
    }

    def fullOuterJoin[K,V1,V2](src1: Traversable[(K,V1)], src2: Traversable[(K,V2)]): Traversable[(K, (Option[V1], Option[V2]))] = {
      for {
        (k, (vs1, vs2)) <- multitool.PairFunctions.cogroup(src1, src2)
        v1 <- if(vs1.isEmpty) Seq(None) else vs1.map { Option(_) }
        v2 <- if(vs2.isEmpty) Seq(None) else vs2.map { Option(_) }
      } yield (k, (v1, v2))
    }
  }

  object CollFunctions {
    def countByValue[T](src: Traversable[T]): Map[T, Long] = PairFunctions.countByKey(src.map { (_, 1L) } )
  }

  object Implicits {
    implicit class MultitoolFunctionsImplicits[T:ClassTag](val self: T) {
      def tap(f: T => Unit) = Functions.tap(f)(self)
      def tapIf(p: Boolean)(f: T => Unit) = Functions.tapIf(p)(f)(self)
      def applyIf(p: Boolean)(f: T => T): T = Functions.applyIf(p)(f)(self)
    }

    implicit class MultitoolPairFunctionsImplicits[K:ClassTag, V:ClassTag](val self: Traversable[(K,V)]) {
      def flatMapValues[T](f: (V) => TraversableOnce[T]) = PairFunctions.flatMapValues(self)(f)
      def mapValues[T](f: (V) => T) = PairFunctions.mapValues(self)(f)
      def groupByKey() = PairFunctions.groupByKey(self)
      def reduceByKey(f: (V,V) => V) = PairFunctions.reduceByKey(self)(f)
      def countByKey() = PairFunctions.countByKey(self)
      def cogroup[V2](src2: Traversable[(K,V2)]) = PairFunctions.cogroup(self, src2)
      def join[V2](src2: Traversable[(K,V2)]) = PairFunctions.join(self, src2)
      def leftOuterJoin[V2](src2: Traversable[(K,V2)]) = PairFunctions.leftOuterJoin(self, src2)
      def rightOuterJoin[V2](src2: Traversable[(K,V2)]) = PairFunctions.rightOuterJoin(self, src2)
      def fullOuterJoin[V2](src2: Traversable[(K,V2)]) = PairFunctions.fullOuterJoin(self, src2)
    }

    implicit class MultitoolCollFunctionsImplicits[T:ClassTag](val self: Traversable[T]) {
      def countByValue() = CollFunctions.countByValue(self)
    }

    implicit class MultitoolRDDFunctionsImplicits[T:ClassTag](val self: RDD[T]) {
      def transform[R:ClassTag](f: T=>R): RDDFunctions.TransformResult[T,R] = {
        RDDFunctions.transform(f)(self)
      }
      def flatTransform[R:ClassTag, C<%TraversableOnce[R]](f: T=>C): RDDFunctions.TransformResult[T,R] = {
        RDDFunctions.flatTransform(f)(self)
      }
      def saveViaTemp(output: String, tempPath: Option[String]=None, codec: Option[Class[_ <: CompressionCodec]]=None)(store: (String, String) => Unit): Unit = {
        fs.saveViaTemp(self)(output, tempPath, codec)(store)
      }
      def saveViaTempWithReplace(output: String, tempPath: Option[String]=None, codec: Option[Class[_ <: CompressionCodec]]=None): Unit = {
        fs.saveViaTempWithReplace(self)(output, tempPath, codec)
      }
      def saveViaTempWithRename(output: String, tempPath: Option[String]=None, codec: Option[Class[_ <: CompressionCodec]]=None): Unit = {
        fs.saveViaTempWithRename(self)(output, tempPath, codec)
      }
    }

    implicit class MultitoolDataFrameFunctionsImplicits(val self: DataFrame) {
      def transform[R:ClassTag](f: Row=>Option[R]): RDDFunctions.TransformResult[Row,R] = {
        DataFrameFunctions.transform(f)(self)
      }
      def transform[R:ClassTag](f: Row=>R)(implicit d: DummyImplicit): RDDFunctions.TransformResult[Row,R] = {
        DataFrameFunctions.transform(f)(self)
      }
    }

    implicit class RichBoolean(val self: Boolean) extends AnyVal {
      def toInt = if(self) 1 else 0
      def toDouble = if(self) 1.0 else 0.0
    }

    implicit class RichInt(val self: Int) extends AnyVal {
      def toBoolean = self == 1
    }
  }
}
