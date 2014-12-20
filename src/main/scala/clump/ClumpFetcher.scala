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

  def apply(input: T): Future[U] =
    get(input).map { option =>
      if (option.isEmpty) throw new RuntimeException(s"Expected result for input: $input")
      option.get
    }

  def getOrElse(input: T, default: U): Future[U] = get(input).map(_.getOrElse(default))

  def get(input: T): Future[Option[U]] =
    fetched.getOrElse(input, flushAndGet(input))

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
