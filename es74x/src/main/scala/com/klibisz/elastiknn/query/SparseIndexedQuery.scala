package com.klibisz.elastiknn.query

import java.util
import java.util.Objects

import com.klibisz.elastiknn.api.ElasticsearchCodec._
import com.klibisz.elastiknn.api._
import com.klibisz.elastiknn.models.SparseIndexedSimilarityFunction
import com.klibisz.elastiknn.storage.ByteArrayCodec
import org.apache.lucene.document.{Field, FieldType, NumericDocValuesField}
import org.apache.lucene.index._
import org.apache.lucene.search._
import org.apache.lucene.search.similarities.BooleanSimilarity
import org.apache.lucene.util.BytesRef

class SparseIndexedQuery(val field: String, val queryVec: Vec.SparseBool, val simFunc: SparseIndexedSimilarityFunction) extends Query {

  private val numTrueDocValuesField: String = SparseIndexedQuery.numTrueDocValueField(field)

  private val intersectionQuery: BooleanQuery = {
    val builder = new BooleanQuery.Builder
    builder.add(new BooleanClause(new DocValuesFieldExistsQuery(numTrueDocValuesField), BooleanClause.Occur.MUST))
    queryVec.trueIndices.foreach { ti =>
      val term = new Term(field, new BytesRef(ByteArrayCodec.encode(ti)))
      val termQuery = new TermQuery(term)
      val clause = new BooleanClause(termQuery, BooleanClause.Occur.SHOULD)
      builder.add(clause)
    }
    builder.build()
  }

  class SparseIndexedWeight(searcher: IndexSearcher) extends Weight(this) {
    searcher.setSimilarity(new BooleanSimilarity)
    private val intersectionWeight = intersectionQuery.createWeight(searcher, ScoreMode.COMPLETE, 1f)
    override def extractTerms(terms: util.Set[Term]): Unit = ()
    override def explain(context: LeafReaderContext, doc: Int): Explanation = ???
    override def scorer(context: LeafReaderContext): Scorer = {
      val numTrueDocValues: NumericDocValues = context.reader.getNumericDocValues(numTrueDocValuesField)
      val scorer = intersectionWeight.scorer(context)
      new SparseIndexedScorer(this, scorer, numTrueDocValues)
    }
    override def isCacheable(ctx: LeafReaderContext): Boolean = false
  }

  class SparseIndexedScorer(weight: Weight, intersectionScorer: Scorer, numericDocValues: NumericDocValues) extends Scorer(weight) {
    override val iterator: DocIdSetIterator = if (intersectionScorer == null) DocIdSetIterator.empty() else intersectionScorer.iterator()
    override def docID(): Int = iterator.docID()
    override def getMaxScore(upTo: Int): Float = Float.MaxValue
    override def score(): Float = {
      val intersection = intersectionScorer.score() - 1 // Subtract one to account for doc values field.
      val docId = docID()
      if (numericDocValues.advanceExact(docID())) {
        val numTrue = numericDocValues.longValue().toInt
        val scoreTry = simFunc(queryVec, intersection.toInt, numTrue)
        scoreTry.get.score.toFloat
      } else throw new RuntimeException(s"Couldn't advance to doc with id [$docId]")
    }
  }

  override def createWeight(searcher: IndexSearcher, scoreMode: ScoreMode, boost: Float): Weight =
    new SparseIndexedWeight(searcher)

  override def toString(field: String): String =
    s"SparseIndexedQuery for field [$field], query vector [${nospaces(queryVec)}], similarity [${simFunc.similarity}]"

  override def equals(other: Any): Boolean = other match {
    case q: SparseIndexedQuery => q.field == field && q.queryVec == queryVec && q.simFunc == simFunc
    case _                     => false
  }

  override def hashCode(): Int = Objects.hashCode(field, queryVec, simFunc)
}

object SparseIndexedQuery {

  def numTrueDocValueField(field: String): String = s"$field.num_true"

  private val trueIndicesFieldType: FieldType = {
    val ft = new FieldType
    ft.setIndexOptions(IndexOptions.DOCS_AND_FREQS)
    ft.setTokenized(false)
    ft.freeze()
    ft
  }

  def index(field: String, vec: Vec.SparseBool): Seq[IndexableField] = {
    vec.trueIndices.map { ti =>
      new Field(field, ByteArrayCodec.encode(ti), trueIndicesFieldType)
    } ++ ExactSimilarityQuery.index(field, vec) :+ new NumericDocValuesField(numTrueDocValueField(field), vec.trueIndices.length)
  }

}
