package lila.coach

import lila.game.{ Game, Pov }
import lila.user.User

object PovToEntry {

  case class RichPov(
    pov: Pov,
    initialFen: Option[String],
    analysis: Option[lila.analyse.Analysis],
    division: chess.Division,
    // accuracy: Option[lila.analyse.Accuracy.DividedAccuracy],
    moveAccuracy: Option[List[Int]])

  def apply(game: Game, userId: String): Fu[Either[Game, Entry]] =
    enrich(game, userId) map (_ flatMap convert toRight game)

  private def enrich(game: Game, userId: String): Fu[Option[RichPov]] =
    lila.game.Pov.ofUserId(game, userId) ?? { pov =>
      lila.game.GameRepo.initialFen(game) zip
        (game.metadata.analysed ?? lila.analyse.AnalysisRepo.doneById(game.id)) map {
          case (fen, an) =>
            val division = chess.Replay.boards(
              moveStrs = game.pgnMoves,
              initialFen = fen,
              variant = game.variant
            ).toOption.fold(chess.Division.empty)(chess.Divider.apply)
            RichPov(
              pov = pov,
              initialFen = fen,
              analysis = an,
              division = division,
              moveAccuracy = an.map { lila.analyse.Accuracy.diffsList(pov, _) }
            ).some
        }
    }

  private def makeCpl(from: RichPov): Option[Grouped[Numbers]] =
    from.moveAccuracy.filter(_.nonEmpty) flatMap { diffs =>
      val div = from.division
      val openingDiffs = div.middle.fold(diffs)(m => diffs.take(m / 2))
      val middleDiffs = div.middle.?? { m =>
        div.end.fold(diffs.drop(m / 2)) { e =>
          diffs.drop(m / 2).take((e - m) / 2)
        }
      }
      val endDiffs = div.end.?? { e =>
        diffs.drop(e / 2)
      }
      val byPhaseOpt = for {
        opening <- Numbers(openingDiffs)
        all <- Numbers(diffs)
      } yield ByPhase(
        opening = opening,
        middle = Numbers(middleDiffs),
        end = Numbers(endDiffs),
        all = all)
      for {
        byPhase <- byPhaseOpt
      } yield Grouped(
        byPhase,
        ByMovetime(Nil),
        ByPieceRole(none, none, none, none, none, none),
        ByPositionQuality(none, none, none, none, none))
    }

  private def convert(from: RichPov): Option[Entry] = for {
    myId <- from.pov.player.userId
    myRating <- from.pov.player.rating
    opRating <- from.pov.opponent.rating
    perfType <- from.pov.game.perfType
  } yield Entry(
    _id = ornicar.scalalib.Random nextStringUppercase 8,
    version = Entry.currentVersion,
    userId = myId,
    gameId = from.pov.game.id,
    color = from.pov.color,
    perf = perfType,
    eco = Ecopening fromGame from.pov.game,
    opponent = Opponent(
      rating = opRating,
      strength = RelativeStrength(opRating - myRating)),
    cpl = makeCpl(from),
    date = from.pov.game.createdAt)
  // cpl: Option[Grouped[Numbers]],
  // movetime: Grouped[Numbers],
  // luck: Option[Grouped[Ratio]],
  // opportunism: Option[Grouped[Ratio]],
  // nbMoves: Grouped[Int],
  // result: Result,
  // status: Status,
  // finalPhase: Phase,
  // ratingDiff: Int,
  // date: DateTime)
  //   )
}