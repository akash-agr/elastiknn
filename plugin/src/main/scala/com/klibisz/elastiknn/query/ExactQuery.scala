package com.klibisz.elastiknn.query

import java.util.Objects

import com.google.common.cache.Cache
import com.klibisz.elastiknn.ELASTIKNN_NAME
import com.klibisz.elastiknn.VecCache._
import com.klibisz.elastiknn.api.Vec
import com.klibisz.elastiknn.models.ExactSimilarityFunction
import com.klibisz.elastiknn.storage.StoredVec
import com.klibisz.elastiknn.storage.StoredVec.Decoder
import org.apache.lucene.document.BinaryDocValuesField
import org.apache.lucene.index.{IndexableField, LeafReaderContext}
import org.apache.lucene.search.{DocValuesFieldExistsQuery, Explanation}
import org.apache.lucene.util.BytesRef
import org.elasticsearch.common.lucene.search.function._

object ExactQuery {

  private class ExactScoreFunction[V <: Vec, S <: StoredVec](val field: String,
                                                             val queryVec: V,
                                                             val simFunc: ExactSimilarityFunction[V, S],
                                                             val cacheOpt: Option[Cache[Key, S]])(implicit codec: StoredVec.Codec[V, S])
      extends ScoreFunction(CombineFunction.REPLACE) {

    override def getLeafScoreFunction(ctx: LeafReaderContext): LeafScoreFunction = {
      val cachedReader = new CachedReader[S](cacheOpt, ctx, field)
      new LeafScoreFunction {
        override def score(docId: Int, subQueryScore: Float): Double = {
          val storedVec = cachedReader(docId)
          simFunc(queryVec, storedVec)
        }
        override def explainScore(docId: Int, subQueryScore: Explanation): Explanation =
          Explanation.`match`(100, s"Elastiknn exact query")
      }
    }

    override def needsScores(): Boolean = false

    override def doEquals(other: ScoreFunction): Boolean = other match {
      case f: ExactScoreFunction[V, S] => field == f.field && queryVec == f.queryVec && simFunc == f.simFunc && cacheOpt == f.cacheOpt
      case _                           => false
    }

    override def doHashCode(): Int = Objects.hash(field, queryVec, simFunc, cacheOpt)
  }

  /**
    * Helper class that makes it easy to read vectors that were stored using the conventions in this class.
    */
  final class CachedReader[S <: StoredVec: Decoder](cacheOpt: Option[Cache[Key, S]], lrc: LeafReaderContext, field: String) {
    private val vecDocVals = lrc.reader.getBinaryDocValues(vecDocValuesField(field))

    private def readBinary(docId: Int): S =
      if (vecDocVals.advanceExact(docId)) {
        val bytesRef = vecDocVals.binaryValue()
        implicitly[StoredVec.Decoder[S]].apply(bytesRef.bytes, bytesRef.offset, bytesRef.length)
      } else throw new RuntimeException(s"Couldn't advance to binary doc values for doc with id [$docId]")

    def apply(docId: Int): S = cacheOpt match {
      case Some(cache) => cache.get(Key(docId, lrc.hashCode()), () => readBinary(docId))
      case None        => readBinary(docId)
    }
  }

  /**
    * Instantiate an exact query, implemented as an Elasticsearch [[FunctionScoreQuery]].
    */
  def apply[V <: Vec, S <: StoredVec](field: String, queryVec: V, simFunc: ExactSimilarityFunction[V, S], cacheOpt: Option[Cache[Key, S]])(
      implicit codec: StoredVec.Codec[V, S]): FunctionScoreQuery = {
    val subQuery = new DocValuesFieldExistsQuery(vecDocValuesField(field))
    val func = new ExactScoreFunction(field, queryVec, simFunc, cacheOpt)
    new FunctionScoreQuery(subQuery, func)
  }

  /**
    * Appends to the given field name to produce the name where a vector is stored.
    */
  def vecDocValuesField(field: String): String = s"$field.$ELASTIKNN_NAME.vector"

  /**
    * Creates and returns a single indexable field that stores the vector contents as a [[BinaryDocValuesField]].
    */
  def index[V <: Vec: StoredVec.Encoder](field: String, vec: V): Seq[IndexableField] = {
    val storedVec = implicitly[StoredVec.Encoder[V]].apply(vec)
    Seq(new BinaryDocValuesField(vecDocValuesField(field), new BytesRef(storedVec)))
  }

}
