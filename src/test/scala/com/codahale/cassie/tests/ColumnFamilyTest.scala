package com.codahale.cassie.tests

import org.scalatest.Spec
import org.scalatest.matchers.MustMatchers
import org.scalatest.mock.MockitoSugar
import org.apache.cassandra.thrift.Cassandra.ServiceToClient
import com.codahale.cassie.codecs.Utf8Codec
import org.mockito.Mockito.{when, verify}
import org.mockito.Matchers.{anyString, any, eq => matchEq, anyListOf}
import org.apache.cassandra.thrift
import scalaj.collection.Imports._
import org.mockito.ArgumentCaptor
import java.nio.ByteBuffer
import com.codahale.cassie.clocks.Clock
import thrift.Mutation
import com.codahale.cassie._
import connection.ClientProvider
import com.twitter.util.{Future, Promise}

class ColumnFamilyTest extends Spec with MustMatchers with MockitoSugar {
  case class SimpleProvider(client: ServiceToClient) extends ClientProvider {
    def map[A](f: ServiceToClient => Future[A]) = f(client)
  }

  case class Fulfillment[A](result: A) extends Promise[A] {
    setValue(result)
  }

  def anyByteBuffer = any(classOf[ByteBuffer])
  def anyColumnParent = any(classOf[thrift.ColumnParent])
  def anySlicePredicate = any(classOf[thrift.SlicePredicate])
  def anyConsistencyLevel = any(classOf[thrift.ConsistencyLevel])
  def newColumn(name: String, value: String, timestamp: Long) = {
    val cosc = new thrift.ColumnOrSuperColumn
    cosc.setColumn(new thrift.Column(Utf8Codec.encode(name), Utf8Codec.encode(value), timestamp))
    cosc
  }
  def b(keyString: String) = ByteBuffer.wrap(keyString.getBytes)

  def setup = {
    import java.util._
    val client = mock[ServiceToClient]
    // stub out some standard cases
    when(client.get_slice(anyByteBuffer, anyColumnParent, anySlicePredicate, anyConsistencyLevel)).thenReturn(Fulfillment(new ArrayList[thrift.ColumnOrSuperColumn]))
    when(client.multiget_slice(anyListOf(classOf[ByteBuffer]), anyColumnParent, anySlicePredicate, anyConsistencyLevel)).thenReturn(Fulfillment(new HashMap[ByteBuffer,List[thrift.ColumnOrSuperColumn]]))
    val provider = SimpleProvider(client)
    val cf = new ColumnFamily("ks", "cf", provider, Utf8Codec, Utf8Codec, Utf8Codec)

    (client, cf)
  }

  describe("getting a columns for a key") {
    val (client, cf) = setup

    it("performs a get_slice with a set of column names") {
      cf.getColumn("key", "name")

      val cp = new thrift.ColumnParent("cf")

      val pred = ArgumentCaptor.forClass(classOf[thrift.SlicePredicate])

      verify(client).get_slice(matchEq(b("key")), matchEq(cp), pred.capture, matchEq(thrift.ConsistencyLevel.QUORUM))

      pred.getValue.getColumn_names.asScala.map { Utf8Codec.decode(_) } must equal(List("name"))
    }

    it("returns none if the column doesn't exist") {
      cf.getColumn("key", "name") must equal(None)
    }

    it("returns a option of a column if it exists") {
      val columns = newColumn("name", "Coda", 2292L) :: Nil

      when(client.get_slice(anyByteBuffer, anyColumnParent, anySlicePredicate, anyConsistencyLevel)).thenReturn(Fulfillment(columns.asJava))

      cf.getColumn("key", "name") must equal(Some(Column("name", "Coda", 2292L)))
    }
  }

  describe("getting a row") {
    val (client, cf) = setup

    it("performs a get_slice with a maxed-out count") {
      cf.getRow("key")

      val cp = new thrift.ColumnParent("cf")

      val range = new thrift.SliceRange(b(""), b(""), false, Int.MaxValue)
      val pred = new thrift.SlicePredicate()
      pred.setSlice_range(range)

      verify(client).get_slice(b("key"), cp, pred, thrift.ConsistencyLevel.QUORUM)
    }

    it("returns a map of column names to columns") {
      val columns = newColumn("name", "Coda", 2292L) ::
                    newColumn("age", "old", 11919L) :: Nil

      when(client.get_slice(anyByteBuffer, anyColumnParent, anySlicePredicate, anyConsistencyLevel)).thenReturn(Fulfillment(columns.asJava))

      cf.getRow("key") must equal(Map(
        "name" -> Column("name", "Coda", 2292L),
        "age" -> Column("age", "old", 11919L)
      ))
    }
  }

  describe("getting a row slice") {
    val (client, cf) = setup

    it("performs a get_slice with the specified count, reversedness, start column name and end column name") {
      val startColumnName = "somewhere"
      val endColumnName   = "overTheRainbow"
      cf.getRowSlice("key", Some(startColumnName), Some(endColumnName), 100, Order.Reversed)

      val cp = new thrift.ColumnParent("cf")

      val range = new thrift.SliceRange(Utf8Codec.encode(startColumnName), Utf8Codec.encode(endColumnName), true, 100)
      val pred  = new thrift.SlicePredicate()
      pred.setSlice_range(range)

      verify(client).get_slice(b("key"), cp, pred, thrift.ConsistencyLevel.QUORUM)
    }
  }

  describe("getting a set of columns for a key") {
    val (client, cf) = setup

    it("performs a get_slice with a set of column names") {
      cf.getColumns("key", Set("name", "age"))

      val cp = new thrift.ColumnParent("cf")

      val pred = ArgumentCaptor.forClass(classOf[thrift.SlicePredicate])

      verify(client).get_slice(matchEq(b("key")), matchEq(cp), pred.capture, matchEq(thrift.ConsistencyLevel.QUORUM))

      pred.getValue.getColumn_names.asScala.map { Utf8Codec.decode(_) } must equal(List("name", "age"))
    }

    it("returns a map of column names to columns") {
      val columns = newColumn("name", "Coda", 2292L) ::
                    newColumn("age", "old", 11919L) :: Nil

      when(client.get_slice(anyByteBuffer, anyColumnParent, anySlicePredicate, anyConsistencyLevel)).thenReturn(Fulfillment(columns.asJava))

      cf.getColumns("key", Set("name", "age")) must equal(Map(
        "name" -> Column("name", "Coda", 2292L),
        "age" -> Column("age", "old", 11919L)
      ))
    }
  }

  describe("getting a column for a set of keys") {
    val (client, cf) = setup

    it("performs a multiget_slice with a column name") {
      cf.consistency(ReadConsistency.One).multigetColumn(Set("key1", "key2"), "name")

      val keys = List("key1", "key2").map{Utf8Codec.encode(_)}.asJava
      val cp = new thrift.ColumnParent("cf")
      val pred = ArgumentCaptor.forClass(classOf[thrift.SlicePredicate])

      verify(client).multiget_slice(matchEq(keys), matchEq(cp), pred.capture, matchEq(thrift.ConsistencyLevel.ONE))

      pred.getValue.getColumn_names.asScala.map { Utf8Codec.decode(_) } must equal(List("name"))
    }

    it("returns a map of keys to a map of column names to columns") {
      val results = Map(
        b("key1") -> (newColumn("name", "Coda", 2292L) :: Nil).asJava,
        b("key2") -> (newColumn("name", "Niki", 422L) :: Nil).asJava
      ).asJava

      when(client.multiget_slice(anyListOf(classOf[ByteBuffer]), anyColumnParent, anySlicePredicate, anyConsistencyLevel)).thenReturn(Fulfillment(results))

      cf.multigetColumn(Set("key1", "key2"), "name") must equal(Map(
        "key1" -> Column("name", "Coda", 2292L),
        "key2" -> Column("name", "Niki", 422L)
      ))
    }

    it("does not explode when the column doesn't exist for a key") {
      val results = Map(
        b("key1") -> (newColumn("name", "Coda", 2292L) :: Nil).asJava,
        b("key2") -> Nil.asInstanceOf[List[thrift.ColumnOrSuperColumn]].asJava
      ).asJava

      when(client.multiget_slice(anyListOf(classOf[ByteBuffer]), anyColumnParent, anySlicePredicate, anyConsistencyLevel)).thenReturn(Fulfillment(results))

      cf.multigetColumn(Set("key1", "key2"), "name") must equal(Map(
        "key1" -> Column("name", "Coda", 2292L)
      ))
    }
  }

  describe("getting a set of columns for a set of keys") {
    val (client, cf) = setup

    it("performs a multiget_slice with a set of column names") {
      cf.consistency(ReadConsistency.One).multigetColumns(Set("key1", "key2"), Set("name", "age"))

      val keys = List("key1", "key2").map{ b(_) }.asJava
      val cp = new thrift.ColumnParent("cf")
      val pred = ArgumentCaptor.forClass(classOf[thrift.SlicePredicate])

      verify(client).multiget_slice(matchEq(keys), matchEq(cp), pred.capture, matchEq(thrift.ConsistencyLevel.ONE))

      pred.getValue.getColumn_names.asScala.map { Utf8Codec.decode(_) } must equal(List("name", "age"))
    }

    it("returns a map of keys to a map of column names to columns") {
      val results = Map(
        b("key1") -> (newColumn("name", "Coda", 2292L) ::
                    newColumn("age", "old", 11919L) :: Nil).asJava,
        b("key2") -> (newColumn("name", "Niki", 422L) ::
                    newColumn("age", "lithe", 129L) :: Nil).asJava
      ).asJava

      when(client.multiget_slice(anyListOf(classOf[ByteBuffer]), anyColumnParent, anySlicePredicate, anyConsistencyLevel)).thenReturn(Fulfillment(results))

      cf.multigetColumns(Set("key1", "key2"), Set("name", "age")) must equal(Map(
        "key1" -> Map(
          "name" -> Column("name", "Coda", 2292L),
          "age" -> Column("age", "old", 11919L)
        ),
        "key2" -> Map(
          "name" -> Column("name", "Niki", 422L),
          "age" -> Column("age", "lithe", 129L)
        )
      ))
    }
  }

  describe("inserting a column") {
    val (client, cf) = setup

    it("performs an insert") {
      cf.insert("key", Column("name", "Coda", 55))

      val cp = ArgumentCaptor.forClass(classOf[thrift.ColumnParent])
      val col = newColumn("name", "Coda", 55).column

      verify(client).insert(matchEq(b("key")), cp.capture, matchEq(col), matchEq(thrift.ConsistencyLevel.QUORUM))

      cp.getValue.getColumn_family must equal("cf")
    }
  }

  describe("removing a column with an implicit timestamp") {
    val (client, cf) = setup
    implicit val clock = new Clock {
      def timestamp = 445
    }

    it("performs a remove") {
      cf.removeColumn("key", "age")

      val cp = ArgumentCaptor.forClass(classOf[thrift.ColumnPath])
      verify(client).remove(matchEq(b("key")), cp.capture, matchEq(445L), matchEq(thrift.ConsistencyLevel.QUORUM))

      cp.getValue.getColumn_family must equal("cf")
      Utf8Codec.decode(cp.getValue.column) must equal("age")
    }
  }

  describe("removing a column with a specific timestamp") {
    val (client, cf) = setup

    it("performs a remove") {
      cf.removeColumnWithTimestamp("key", "age", 55)

      val cp = ArgumentCaptor.forClass(classOf[thrift.ColumnPath])
      verify(client).remove(matchEq(b("key")), cp.capture, matchEq(55L), matchEq(thrift.ConsistencyLevel.QUORUM))

      cp.getValue.getColumn_family must equal("cf")
      Utf8Codec.decode(cp.getValue.column) must equal("age")
    }
  }

  describe("removing a row with a specific timestamp") {
    val (client, cf) = setup

    it("performs a remove") {
      cf.removeRowWithTimestamp("key", 55)

      val cp = ArgumentCaptor.forClass(classOf[thrift.ColumnPath])
      verify(client).remove(matchEq(b("key")), cp.capture, matchEq(55L), matchEq(thrift.ConsistencyLevel.QUORUM))

      cp.getValue.column_family must equal("cf")
      cp.getValue.column must be(null)
    }
  }

  describe("removing a row with an implicit timestamp") {
    val (client, cf) = setup
    implicit val clock = new Clock {
      def timestamp = 445
    }

    it("performs a remove") {
      cf.removeRow("key")

      val cp = ArgumentCaptor.forClass(classOf[thrift.ColumnPath])
      verify(client).remove(matchEq(b("key")), cp.capture, matchEq(445L), matchEq(thrift.ConsistencyLevel.QUORUM))

      cp.getValue.column_family must equal("cf")
      cp.getValue.column must be(null)
    }
  }

  describe("removing a set of columns from a row with an implicit timestamp") {
    val (client, cf) = setup
    implicit val clock = new Clock {
      def timestamp = 445
    }

    it("performs a batch mutate") {
      cf.removeColumns("key", Set("one", "two"))

      val map = ArgumentCaptor.forClass(classOf[java.util.Map[ByteBuffer, java.util.Map[String, java.util.List[Mutation]]]])

      verify(client).batch_mutate(map.capture, matchEq(thrift.ConsistencyLevel.QUORUM))

      val mutations = map.getValue
      val mutation = mutations.get(b("key")).get("cf").get(0)
      val deletion = mutation.getDeletion

      deletion.getTimestamp must equal(445L)
      deletion.getPredicate.getColumn_names.asScala.map { Utf8Codec.decode(_) }.sortWith { _ < _ } must equal(List("one", "two"))
    }
  }

  describe("removing a set of columns from a row with an explicit timestamp") {
    val (client, cf) = setup

    it("performs a batch mutate") {
      cf.removeColumnsWithTimestamp("key", Set("one", "two"), 33)

      val map = ArgumentCaptor.forClass(classOf[java.util.Map[ByteBuffer, java.util.Map[String, java.util.List[Mutation]]]])

      verify(client).batch_mutate(map.capture, matchEq(thrift.ConsistencyLevel.QUORUM))

      val mutations = map.getValue
      val mutation = mutations.get(b("key")).get("cf").get(0)
      val deletion = mutation.getDeletion

      deletion.getTimestamp must equal(33)
      deletion.getPredicate.getColumn_names.asScala.map { Utf8Codec.decode(_) }.sortWith { _ < _ } must equal(List("one", "two"))
    }
  }

  describe("performing a batch mutation") {
    val (client, cf) = setup

    it("performs a batch_mutate") {
      cf.consistency(WriteConsistency.All).batch() { batch =>
        batch.insert("key", Column("name", "value", 201))
      }

      val map = ArgumentCaptor.forClass(classOf[java.util.Map[ByteBuffer, java.util.Map[String, java.util.List[Mutation]]]])

      verify(client).batch_mutate(map.capture, matchEq(thrift.ConsistencyLevel.ALL))

      val mutations = map.getValue
      val mutation = mutations.get(b("key")).get("cf").get(0)
      val col = mutation.getColumn_or_supercolumn.getColumn
      Utf8Codec.decode(col.name) must equal("name")
      Utf8Codec.decode(col.value) must equal("value")
      col.getTimestamp must equal(201)
    }
  }

  describe("iterating through all columns of all rows") {
    val (client, cf) = setup

    it("returns a ColumnIterator with an all-column predicate") {
      val iterator = cf.rowIterator(16).asInstanceOf[ColumnIterator[String, String, String]]

      iterator.cf must equal(cf)
      iterator.startKey must equal(b(""))
      iterator.endKey must equal(b(""))
      iterator.batchSize must equal(16)
      iterator.predicate.getColumn_names must be(null)
      iterator.predicate.getSlice_range.getStart must equal(Array[Byte]())
      iterator.predicate.getSlice_range.getFinish must equal(Array[Byte]())
      iterator.predicate.getSlice_range.getCount must equal(Int.MaxValue)
    }
  }

  describe("iterating through one column of all rows") {
    val (client, cf) = setup

    it("returns a ColumnIterator with a single-column predicate") {
      val iterator = cf.columnIterator(16, "name").asInstanceOf[ColumnIterator[String, String, String]]

      iterator.cf must equal(cf)
      iterator.startKey must equal(b(""))
      iterator.endKey must equal(b(""))
      iterator.batchSize must equal(16)
      iterator.predicate.getColumn_names.asScala.map { Utf8Codec.decode(_) } must be(List("name"))
    }
  }

  describe("iterating through a set of columns of all rows") {
    val (client, cf) = setup

    it("returns a ColumnIterator with a column-list predicate") {
      val iterator = cf.columnsIterator(16, Set("name", "motto")).asInstanceOf[ColumnIterator[String, String, String]]

      iterator.cf must equal(cf)
      iterator.startKey must equal(b(""))
      iterator.endKey must equal(b(""))
      iterator.batchSize must equal(16)
      iterator.predicate.getColumn_names.asScala.map { Utf8Codec.decode(_) }.toSet must be(Set("name", "motto"))
    }
  }
}
