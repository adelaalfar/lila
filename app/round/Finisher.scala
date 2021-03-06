package lila
package round

import game.{ GameRepo, DbGame, Pov }
import user.UserRepo
import i18n.I18nKey.{ Select ⇒ SelectI18nKey }
import elo.EloUpdater
import chess.{ EloCalculator, Status, Color }
import Status._
import Color._

import scalaz.effects._
import play.api.Play.current
import play.api.libs.concurrent.Akka

final class Finisher(
    userRepo: UserRepo,
    gameRepo: GameRepo,
    messenger: Messenger,
    eloUpdater: EloUpdater,
    eloCalculator: EloCalculator,
    finisherLock: FinisherLock,
    indexGame: DbGame ⇒ IO[Unit],
    tournamentOrganizerActorName: String) {

  type ValidIOEvents = Valid[IO[List[Event]]]

  private lazy val tournamentOrganizerActor =
    Akka.system.actorFor("/user/" + tournamentOrganizerActorName)

  def abort(pov: Pov): ValidIOEvents =
    if (pov.game.abortable) finish(pov.game, Aborted)
    else !!("game is not abortable")

  def forceAbort(game: DbGame): ValidIOEvents =
    if (game.playable) finish(game, Aborted)
    else !!("game is not playable, cannot be force aborted")

  def resign(pov: Pov): ValidIOEvents =
    if (pov.game.resignable) finish(pov.game, Resign, Some(!pov.color))
    else !!("game is not resignable")

  def resignForce(pov: Pov): ValidIOEvents =
    if (pov.game.resignable && !pov.game.hasAi)
      finish(pov.game, Timeout, Some(pov.color))
    else !!("game is not resignable")

  def drawClaim(pov: Pov): ValidIOEvents = pov match {
    case Pov(game, color) if game.playable && game.player.color == color && game.toChessHistory.threefoldRepetition ⇒ finish(game, Draw)
    case Pov(game, color) ⇒ !!("game is not threefold repetition")
  }

  def drawAccept(pov: Pov): ValidIOEvents =
    if (pov.opponent.isOfferingDraw)
      finish(pov.game, Draw, None, Some(_.drawOfferAccepted))
    else !!("opponent is not proposing a draw")

  def outoftime(game: DbGame): ValidIOEvents = game.outoftimePlayer.fold(
    player ⇒ finish(game, Outoftime,
      Some(!player.color) filter game.toChess.board.hasEnoughMaterialToMate),
    !!("no outoftime applicable " + game.clock.fold(_.remainingTimes, "-"))
  )

  def outoftimes(games: List[DbGame]): List[IO[Unit]] =
    games map { g ⇒
      outoftime(g).fold(
        msgs ⇒ putStrLn(g.id + " " + msgs.shows),
        _ map (_ ⇒ Unit) // events are lost
      ): IO[Unit]
    }

  def moveFinish(game: DbGame, color: Color): IO[List[Event]] =
    (game.status match {
      case Mate                        ⇒ finish(game, Mate, Some(color))
      case status @ (Stalemate | Draw) ⇒ finish(game, status)
      case _                           ⇒ success(io(Nil)): ValidIOEvents
    }) | io(Nil)

  private def finish(
    game: DbGame,
    status: Status,
    winner: Option[Color] = None,
    message: Option[SelectI18nKey] = None): Valid[IO[List[Event]]] =
    if (finisherLock isLocked game) !!("game finish is locked")
    else success(for {
      _ ← finisherLock lock game
      p1 = game.finish(status, winner)
      p2 ← message.fold(
        m ⇒ messenger.systemMessage(p1.game, m) map p1.++,
        io(p1)
      )
      _ ← gameRepo save p2
      g = p2.game
      winnerId = winner flatMap (g.player(_).userId)
      _ ← gameRepo.finish(g.id, winnerId)
      _ ← updateElo(g)
      _ ← incNbGames(g, White) doIf (g.status >= Status.Mate)
      _ ← incNbGames(g, Black) doIf (g.status >= Status.Mate)
      _ ← indexGame(g)
      _ ← io { tournamentOrganizerActor ! FinishGame(g) }
    } yield p2.events)

  private def incNbGames(game: DbGame, color: Color): IO[Unit] =
    game.player(color).userId.fold(
      id ⇒ userRepo.incNbGames(id, game.rated, game.hasAi,
        result = game.wonBy(color).fold(_.fold(1, -1), 0).some filterNot (_ ⇒ game.hasAi || game.aborted)
      ),
      io()
    )

  private def updateElo(game: DbGame): IO[Unit] = ~{
    for {
      whiteUserId ← game.player(White).userId
      blackUserId ← game.player(Black).userId
      if whiteUserId != blackUserId
    } yield for {
      whiteUserOption ← userRepo byId whiteUserId
      blackUserOption ← userRepo byId blackUserId
      _ ← ~(whiteUserOption |@| blackUserOption).apply(
        (whiteUser, blackUser) ⇒ {
          val (whiteElo, blackElo) = eloCalculator.calculate(whiteUser, blackUser, game.winnerColor)
          val (whiteDiff, blackDiff) = (whiteElo - whiteUser.elo, blackElo - blackUser.elo)
          val cheaterWin = (whiteDiff > 0 && whiteUser.engine) || (blackDiff > 0 && blackUser.engine)
          gameRepo.setEloDiffs(game.id, whiteDiff, blackDiff) >>
            eloUpdater.game(whiteUser, whiteElo, blackUser.elo) >>
            eloUpdater.game(blackUser, blackElo, whiteUser.elo) doUnless cheaterWin
        }
      )
    } yield ()
  } doUnless (!game.finished || !game.rated || game.turns < 2)
}
