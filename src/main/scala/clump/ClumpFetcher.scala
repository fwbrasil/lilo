package clump

import com.twitter.util.{ Future, Promise }
import scala.collection.mutable.{ Map => MutableMap }

private[clump] final class ClumpFetcher[T, U](source: ClumpSource[T, U]) {

  private[this] val fetches = MutableMap[T, Promise[Option[U]]]()

  def get(input: T) =
    synchronized {
      fetches.getOrElseUpdate(input, Promise[Option[U]])
    }

  def flush =
    synchronized {
      Future.collect(flushInBatches).unit
    }


  private[this] def flushInBatches =
    pendingFetches
      .grouped(source.maxBatchSize)
      .toList
      .map(fetchBatch)

  private[this] def fetchBatch(batch: Set[T]) = {
    val results = fetchWithRetries(batch, 0)
    for (input <- batch) {
      val fetch = fetches(input)
      val fetchResult = results.map(_.get(input))
      fetchResult.proxyTo(fetch)
    }
    results
  }

  private[this] def fetchWithRetries(batch: Set[T], retries: Int): Future[Map[T, U]] =
    source.fetch(batch).rescue {
      case exception if (maxRetries(exception) > retries) =>
        fetchWithRetries(batch, retries + 1)
    }

  private[this] def maxRetries(exception: Throwable) =
    source._maxRetries.lift(exception).getOrElse(0)

  private[this] def pendingFetches =
    fetches.collect {
      case (key, fetch) if (!fetch.poll.isDefined) => key
    }.toSet
}
