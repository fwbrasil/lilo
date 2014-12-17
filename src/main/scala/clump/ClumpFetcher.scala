package clump

import com.twitter.util.Future

class ClumpFetcher[T, U](source: ClumpSource[T, U]) {

  private var pending = Set[T]()
  private var fetched = Map[T, Future[Option[U]]]()

  def append(input: T) =
    synchronized {
      retryFailures(input)
      pending += input
    }

  def run(input: T, strict: Boolean) =
    failIf(strict, input, fetched.getOrElse(input, flushAndGet(input)))

  def failIf(strict: Boolean, input: T, future: Future[Option[U]]) = {
    future.map(option => {
      if (strict && option.isEmpty) throw new RuntimeException(s"Expected result for input '$input'")
      option
    })
  }

  private def flushAndGet(input: T): Future[Option[U]] =
    flush.flatMap { _ => fetched(input) }

  private def flush =
    synchronized {
      val toFetch = pending -- fetched.keys
      val fetch = fetchInBatches(toFetch)
      pending = Set()
      fetch
    }

  private def fetchInBatches(toFetch: Set[T]) =
    Future.collect {
      toFetch.grouped(source.maxBatchSize).toList.map { batch =>
        val results = source.fetch(batch)
        for (input <- batch)
          fetched += input -> results.map(_.get(input))
        results
      }
    }

  private def retryFailures(input: T) =
    fetched.get(input).map { result =>
      if (result.poll.forall(_.isThrow))
        fetched -= input
    }
}
