package lila.system
package memo

import model._
import lila.chess.{ Color, White, Black }
import scalaz.effects._

final class AliveMemo(hardTimeout: Int, softTimeout: Int) {

  private val cache = Builder.expiry[String, Long](hardTimeout)

  private val bigLatency = 9999

  def get(gameId: String, color: Color): Option[Long] = Option {
    cache getIfPresent (toKey(gameId, color))
  }

  def put(gameId: String, color: Color): IO[Unit] = io {
    put(gameId, color, now)
  }

  def put(gameId: String, color: Color, time: Long): IO[Unit] = io {
    cache.put(toKey(gameId, color), time)
  }

  def transfer(g1: String, c1: Color, g2: String, c2: Color): IO[Unit] = io {
    get(g1, c1) foreach { put(g2, c2, _) }
  }

  def latency(gameId: String, color: Color): Int =
    get(gameId, color) map { time ⇒ (now - time).toInt } getOrElse bigLatency

  /**
   * Get player activity (or connectivity)
   * 2 - good connectivity
   * 1 - recently offline
   * 0 - offline for long time
   */
  def activity(game: DbGame, player: DbPlayer): Int =
    if (player.isAi) 2
    else latency(game.id, player.color) |> { l ⇒
      if (l <= softTimeout) 2
      else if (l <= hardTimeout) 1
      else 0
    }

  def count = cache.size

  private def toKey(gameId: String, color: Color) = gameId + "." + color.letter

  private def now = System.currentTimeMillis
}