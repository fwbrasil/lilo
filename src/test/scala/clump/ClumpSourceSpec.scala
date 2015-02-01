package clump

import org.specs2.mutable.Specification
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import com.twitter.util.{ Future, Await }
import org.mockito.Mockito._
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ClumpSourceSpec extends Spec {

  trait Context extends Scope {
    trait TestRepository {
      def fetch(inputs: Set[Int]): Future[Map[Int, Int]]
      def fetchWithScope(fromScope: Int, inputs: Set[Int]): Future[Map[Int, Int]]
    }

    val repo = smartMock[TestRepository]
  }

  "allows to create a clump source (ClumpSource.from)" >> {
    "set input" in {
      def fetch(inputs: Set[Int]) = Future.value(inputs.map(i => i -> i.toString).toMap)
      val source = Clump.sourceFrom(fetch)
      clumpResult(source.get(1)) mustEqual Some("1")
    }
    "list input" in {
      def fetch(inputs: List[Int]) = Future.value(inputs.map(i => i -> i.toString).toMap)
      val source = Clump.sourceFrom(fetch)
      clumpResult(source.get(1)) mustEqual Some("1")
    }
  }

  "allows to create a clump source with key function (ClumpSource.apply)" >> {
    "set input" in {
      def fetch(inputs: Set[Int]) = Future.value(inputs.map(_.toString))
      val source = Clump.source(fetch)(_.toInt)
      clumpResult(source.get(1)) mustEqual Some("1")
    }
    "seq input" in {
      def fetch(inputs: Seq[Int]) = Future.value(inputs.map(_.toString))
      val source = Clump.source(fetch)(_.toInt)
      clumpResult(source.get(1)) mustEqual Some("1")
    }
  }

  "allows to create a clump source with zip as the key function (ClumpSource.zip)" in {
    def fetch(inputs: List[Int]) = Future.value(inputs.map(_.toString))
    val source = Clump.sourceZip(fetch)
    clumpResult(source.get(1)) mustEqual Some("1")
  }

  "allows to create a clump source from various input/ouput type fetch functions (ClumpSource.apply)" in {
    def setToSet: Set[Int] => Future[Set[String]] = { inputs => Future.value(inputs.map(_.toString)) }
    def listToList: List[Int] => Future[List[String]] = { inputs => Future.value(inputs.map(_.toString)) }
    def iterableToIterable: Iterable[Int] => Future[Iterable[String]] = { inputs => Future.value(inputs.map(_.toString)) }
    def setToList: Set[Int] => Future[List[String]] = { inputs => Future.value(inputs.map(_.toString).toList) }
    def listToSet: List[Int] => Future[Set[String]] = { inputs => Future.value(inputs.map(_.toString).toSet) }
    def setToIterable: Set[Int] => Future[Iterable[String]] = { inputs => Future.value(inputs.map(_.toString)) }
    def listToIterable: List[Int] => Future[Iterable[String]] = { inputs => Future.value(inputs.map(_.toString)) }
    def iterableToList: Iterable[Int] => Future[List[String]] = { inputs => Future.value(inputs.map(_.toString).toList) }
    def iterableToSet: Iterable[Int] => Future[List[String]] = { inputs => Future.value(inputs.map(_.toString).toList) }

    def testSource(source: ClumpSource[Int, String]) =
      clumpResult(source.get(1, 2)) mustEqual Some(List("1", "2"))

    def extractId(string: String) = string.toInt

    testSource(ClumpSource(setToSet)(extractId))
    testSource(ClumpSource(listToList)(extractId))
    testSource(ClumpSource(iterableToIterable)(extractId))
    testSource(ClumpSource(setToList)(extractId))
    testSource(ClumpSource(listToSet)(extractId))
    testSource(ClumpSource(setToIterable)(extractId))
    testSource(ClumpSource(listToIterable)(extractId))
    testSource(ClumpSource(iterableToList)(extractId))
    testSource(ClumpSource(iterableToSet)(extractId))
  }

  "allows to create a clump source from various input/ouput type fetch functions (ClumpSource.from)" in {

    def setToMap: Set[Int] => Future[Map[Int, String]] = { inputs => Future.value(inputs.map(input => (input, input.toString)).toMap) }
    def listToMap: List[Int] => Future[Map[Int, String]] = { inputs => Future.value(inputs.map(input => (input, input.toString)).toMap) }
    def iterableToMap: Iterable[Int] => Future[Map[Int, String]] = { inputs => Future.value(inputs.map(input => (input, input.toString)).toMap) }

    def testSource(source: ClumpSource[Int, String]) =
      clumpResult(source.get(1, 2)) mustEqual Some(List("1", "2"))

    testSource(ClumpSource.from(setToMap))
    testSource(ClumpSource.from(listToMap))
    testSource(ClumpSource.from(iterableToMap))
  }

  "fetches an individual clump" in new Context {
    val source = Clump.sourceFrom(repo.fetch)

    when(repo.fetch(Set(1))).thenReturn(Future(Map(1 -> 2)))

    clumpResult(source.get(1)) mustEqual Some(2)

    verify(repo).fetch(Set(1))
    verifyNoMoreInteractions(repo)
  }

  "fetches multiple clumps" >> {

    "using list" in new Context {
      val source = Clump.sourceFrom(repo.fetch)

      when(repo.fetch(Set(1, 2))).thenReturn(Future(Map(1 -> 10, 2 -> 20)))

      val clump = source.get(List(1, 2))

      clumpResult(clump) mustEqual Some(List(10, 20))

      verify(repo).fetch(Set(1, 2))
      verifyNoMoreInteractions(repo)
    }

    "using varargs" in new Context {
      val source = Clump.sourceFrom(repo.fetch)

      when(repo.fetch(Set(1, 2))).thenReturn(Future(Map(1 -> 10, 2 -> 20)))

      val clump = source.get(1, 2)

      clumpResult(clump) mustEqual Some(List(10, 20))

      verify(repo).fetch(Set(1, 2))
      verifyNoMoreInteractions(repo)
    }
  }

  "can be used as a non-singleton" >> {
    "without values from the outer scope" in new Context {
      def source = Clump.sourceFrom(repo.fetch)

      when(repo.fetch(Set(1))).thenReturn(Future(Map(1 -> 2)))

      val clump =
        Clump.collect {
          (for (i <- 0 until 5) yield {
            source.get(List(1))
          }).toList
        }

      Await.result(clump.get)

      verify(repo).fetch(Set(1))
      verifyNoMoreInteractions(repo)
    }

    "with values from the outer scope" in new Context {
      val scope = 1
      def source = Clump.sourceFrom((inputs: Set[Int]) => repo.fetchWithScope(scope, inputs))

      when(repo.fetchWithScope(scope, Set(1))).thenReturn(Future(Map(1 -> 2)))

      val clump =
        Clump.collect {
          (for (i <- 0 until 5) yield {
            source.get(List(1))
          }).toList
        }

      Await.result(clump.get)

      verify(repo).fetchWithScope(1, Set(1))
      verifyNoMoreInteractions(repo)
    }
  }
}