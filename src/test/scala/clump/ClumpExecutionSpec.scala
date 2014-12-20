package clump

import org.specs2.mutable.Specification
import org.specs2.mock.Mockito
import com.twitter.util.Future
import scala.collection.mutable.ListBuffer
import org.specs2.specification.Scope
import org.junit.runner.RunWith
import org.specs2.runner.JUnitRunner
import com.twitter.util.Promise

@RunWith(classOf[JUnitRunner])
class ClumpExecutionSpec extends Spec {

  trait Context extends Scope {
    val source1Fetches = ListBuffer[Set[Int]]()
    val source2Fetches = ListBuffer[Set[Int]]()

    def fetchFunction(fetches: ListBuffer[Set[Int]], inputs: Set[Int]) = {
      fetches += inputs
      Future.value(inputs.map(i => i -> i * 10).toMap)
    }

    val source1 = Clump.sourceFrom((i: Set[Int]) => fetchFunction(source1Fetches, i))
    val source2 = Clump.sourceFrom((i: Set[Int]) => fetchFunction(source2Fetches, i))
  }

  "batches requests" >> {

    "for multiple clumps created from traversed inputs" in new Context {
      val clump =
        Clump.traverse(List(1, 2, 3, 4)) {
          i =>
            if (i <= 2)
              source1(i)
            else
              source2(i)
        }

      clumpResult(clump) ==== List(10, 20, 30, 40)
      source1Fetches mustEqual List(Set(1, 2))
      source2Fetches mustEqual List(Set(3, 4))
    }

    "for multiple clumps collected into only one clump" in new Context {
      val clump = Clump.collect(source1(1), source1(2), source2(3), source2(4))

      clumpResult(clump) ==== List(10, 20, 30, 40)
      source1Fetches mustEqual List(Set(1, 2))
      source2Fetches mustEqual List(Set(3, 4))
    }

    "for clumps created inside nested flatmaps" in new Context {
      val clump1 = Clump.value(1).flatMap(source1(_)).flatMap(source2(_))
      val clump2 = Clump.value(2).flatMap(source1(_)).flatMap(source2(_))

      clumpResult(Clump.collect(clump1, clump2)) ==== List(100, 200)
      source1Fetches mustEqual List(Set(1, 2))
      source2Fetches mustEqual List(Set(20, 10))
    }

    "for clumps composed using for comprehension" >> {

      "one level" in new Context {
        val clump =
          for {
            int <- Clump.collect(source1(1), source1(2), source2(3), source2(4))
          } yield int

        clumpResult(clump) ==== (List(10, 20, 30, 40))
        source1Fetches mustEqual List(Set(1, 2))
        source2Fetches mustEqual List(Set(3, 4))
      }

      "two levels" in new Context {
        val clump =
          for {
            ints1 <- Clump.collect(source1(1), source1(2))
            ints2 <- Clump.collect(source2(3), source2(4))
          } yield (ints1, ints2)

        clumpResult(clump) ==== (List(10, 20), List(30, 40))
        source1Fetches mustEqual List(Set(1, 2))
        source2Fetches mustEqual List(Set(3, 4))
      }

      "using a join" in new Context {
        val clump =
          for {
            ints1 <- Clump.collect(source1(1), source1(2))
            ints2 <- source2(3).join(source2(4))
          } yield (ints1, ints2)

        clumpResult(clump) ==== (List(10, 20), (30, 40))
        source1Fetches mustEqual List(Set(1, 2))
        source2Fetches mustEqual List(Set(3, 4))
      }

      "complex scenario" in new Context {
        val clump: Clump[(Int, Int, List[Int], List[Int], (Int, Int), (List[Int], Int))] =
          for {
            const1 <- Clump.value(1)
            const2 <- Clump.value(2)
            collect1 <- Clump.collect(source1(const1), source2(const2))
            collect2 <- Clump.collect(source1(const1), source2(const2))
            join1 <- Clump.value(4).join(Clump.value(5))
            join2 <- source1.list(collect1).join(source2(join1._2))
          } yield (const1, const2, collect1, collect2, join1, join2)

        clumpResult(clump) ==== (1, 2, List(10, 20), List(10, 20), (4, 5), (List(100, 200), 50))
        source1Fetches mustEqual List(Set(1), Set(10, 20))
        source2Fetches mustEqual List(Set(2), Set(5))
      }
    }
  }

  "executes joined clumps in parallel" in new Context {
    var promises = List[Promise[Map[Int, Int]]]()

    override def fetchFunction(fetches: ListBuffer[Set[Int]], inputs: Set[Int]) = {
      val promise = Promise[Map[Int, Int]]()
      promises :+= promise
      promise
    }

    source1(1).join(source2(2)).run

    promises.size ==== 2
  }

  "short-circuits the computation in case of a failure" in new Context {
    val clump = Clump.exception[Int](new IllegalStateException).map(_ => throw new NullPointerException)

    try {
      clumpResult(clump)
      ko("expected IllegalStateException")
    } catch {
      case e: IllegalStateException => ok
      case _: Throwable => ko("expected IllegalStateException")
    }
  }
}