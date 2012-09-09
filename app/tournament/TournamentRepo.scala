package lila
package tournament

import com.novus.salat._
import com.novus.salat.dao._
import com.mongodb.casbah.MongoCollection
import com.mongodb.casbah.query.Imports._
import scalaz.effects._
import org.joda.time.DateTime
import org.scala_tools.time.Imports._

class TournamentRepo(collection: MongoCollection)
    extends SalatDAO[Tournament, String](collection) {

  def byId(id: String): IO[Option[Tournament]] = io {
    findOneById(id)
  }

  def saveIO(tournament: Tournament): IO[Unit] = io {
    save(tournament)
  }

  val lastTournament: IO[Option[Tournament]] = io {
    find(DBObject()).sort(sortCreated).limit(1).toList.headOption
  }

  private def sortCreated = DBObject("createdAt" -> -1)
}