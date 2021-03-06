package lila
package search

import game.{ GameRepo, PgnRepo, DbGame, Query ⇒ GameQuery }

import scalaz.effects._
import com.codahale.jerkson.Json

import org.elasticsearch.action.search.SearchResponse

import scalastic.elasticsearch.{ Indexer ⇒ EsIndexer }
import com.mongodb.casbah.query.Imports._

final class Indexer(
    es: EsIndexer,
    gameRepo: GameRepo,
    pgnRepo: PgnRepo,
    queue: Queue) {

  val indexName = "lila"
  val typeName = "game"

  def rebuildAll: IO[Unit] = for {
    _ ← clear
    nb ← indexQuery(DBObject())
    _ ← io { es.waitTillCountAtLeast(Seq(indexName), typeName, nb) }
    _ ← optimize
  } yield ()

  def search(request: SearchRequest): SearchResponse = request.in(indexName, typeName)(es)

  def count(request: CountRequest): Int = request.in(indexName, typeName)(es)

  val indexQueue: IO[Unit] = for {
    ids ← queue next 2000
    _ ← ids.toNel.fold(
      neIds ⇒ for {
        _ ← putStrLn("[search] indexing %d games" format neIds.list.size)
        _ ← indexQuery("_id" $in neIds.list)
        _ ← queue remove neIds.list
      } yield (),
      io()
    )
  } yield ()

  private def clear: IO[Unit] = io {
    es.deleteIndex(Seq(indexName))
  } map (_ ⇒ ()) except { e ⇒ putStrLn("Index does not exist yet") } map { _ ⇒
    es.createIndex(indexName, settings = Map())
    es.waitTillActive()
    es.putMapping(indexName, typeName, Json generate Map(typeName -> Game.mapping))
    es.refresh()
  }

  private def indexQuery(query: DBObject): IO[Int] = io {
    val cursor = gameRepo find (GameQuery.frozen ++ query) sort GameQuery.sortCreated //limit 3000
    val size = cursor.count
    var nb = 0
    for (games ← cursor grouped 5000) {
      val pgns = games.map(g ⇒ (pgnRepo get g.id).unsafePerformIO)
      val gamesWithPgn = games zip pgns
      if (size > 1000) println("Indexing %d of %d".format(nb, size))
      val actions = gamesWithPgn map {
        case (game, pgn) ⇒ game.decode map Game.from(pgn)
      } collect {
        case Some((id, doc)) ⇒
          es.index_prepare(indexName, typeName, id, Json generate doc).request
      }
      if (actions.nonEmpty) {
        es bulk actions
        nb = nb + actions.size
      }
    }
    nb
  }

  val optimize: IO[Unit] = io {
    es.optimize(Seq(indexName))
  }

  def toGames(response: SearchResponse): IO[List[DbGame]] =
    gameRepo games {
      response.hits.hits.toList map (_.id)
    }
}
