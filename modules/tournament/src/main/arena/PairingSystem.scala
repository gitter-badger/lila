package lila.tournament
package arena

import lila.common.Chronometer
import lila.tournament.{ PairingSystem => AbstractPairingSystem }
import lila.user.UserRepo

import scala.util.Random

object PairingSystem extends AbstractPairingSystem {
  type P = (String, String)

  case class Data(
    tour: Tournament,
    lastOpponents: Pairing.LastOpponents,
    ranking: Map[String, Int],
    onlyTwoActivePlayers: Boolean)

  // if waiting users can make pairings
  // then pair all users
  def createPairings(
    tour: Tournament,
    users: WaitingUsers,
    ranking: Ranking): Fu[Pairings] = {
    for {
      lastOpponents <- PairingRepo.lastOpponents(tour.id, users.all, Math.min(100, users.size * 4))
      onlyTwoActivePlayers <- (tour.nbPlayers > 20).fold(
        fuccess(false),
        PlayerRepo.countActive(tour.id).map(2==))
      data = Data(tour, lastOpponents, ranking, onlyTwoActivePlayers)
      preps <- if (lastOpponents.hash.isEmpty) evenOrAll(data, users)
      else makePreps(data, users.waiting) flatMap {
        case Nil => fuccess(Nil)
        case _   => evenOrAll(data, users)
      }
      pairings <- preps.map { prep =>
        UserRepo.firstGetsWhite(prep.user1.some, prep.user2.some) map prep.toPairing
      }.sequenceFu
    } yield pairings
  }.logIfSlow(500, "tourpairing") { pairings =>
    s"createPairing http://lichess.org/tournament/${tour.id} ${pairings.size} pairings"
  }

  private def evenOrAll(data: Data, users: WaitingUsers) =
    makePreps(data, users.evenNumber) flatMap {
      case Nil if users.isOdd => makePreps(data, users.all)
      case x                  => fuccess(x)
    }

  val pairingGroupSize = 20

  private def makePreps(data: Data, users: List[String]): Fu[List[Pairing.Prep]] = {
    import data._
    if (users.size < 2) fuccess(Nil)
    else PlayerRepo.rankedByTourAndUserIds(tour.id, users, ranking).logIfSlow(200, "tourpairing") { ranked =>
      s"makePreps.rankedByTourAndUserIds http://lichess.org/tournament/${data.tour.id} ${users.size} users, ${ranked.size} ranked"
    } map { idles =>
      if (lastOpponents.hash.isEmpty) naivePairings(tour, idles)
      else idles.grouped(pairingGroupSize).toList match {
        case a :: b :: c :: _ => smartPairings(data, a) ::: smartPairings(data, b) ::: naivePairings(tour, c take pairingGroupSize)
        case a :: b :: Nil    => smartPairings(data, a) ::: smartPairings(data, b)
        case a :: Nil         => smartPairings(data, a)
        case Nil              => Nil
      }
    }
  }.logIfSlow(200, "tourpairing") { preps =>
    s"makePreps http://lichess.org/tournament/${data.tour.id} ${users.size} users, ${preps.size} preps"
  }

  private def naivePairings(tour: Tournament, players: RankedPlayers): List[Pairing.Prep] =
    players grouped 2 collect {
      case List(p1, p2) => Pairing.prep(tour, p1.player, p2.player)
    } toList

  private def smartPairings(data: Data, players: RankedPlayers): List[Pairing.Prep] = players.nonEmpty ?? {
    import data._

    type Score = Int
    type RankedPairing = (RankedPlayer, RankedPlayer)
    type Combination = List[RankedPairing]

    def justPlayedTogether(u1: String, u2: String): Boolean =
      lastOpponents.hash.get(u1).contains(u2) || lastOpponents.hash.get(u2).contains(u1)

    def veryMuchJustPlayedTogether(u1: String, u2: String): Boolean =
      lastOpponents.hash.get(u1).contains(u2) && lastOpponents.hash.get(u2).contains(u1)

    // lower is better
    def pairingScore(pair: RankedPairing): Score = pair match {
      case (a, b) => Math.abs(a.rank - b.rank) * 1000 +
        Math.abs(a.player.rating - b.player.rating) +
        justPlayedTogether(a.player.userId, b.player.userId).?? {
          if (veryMuchJustPlayedTogether(a.player.userId, b.player.userId)) 9000 * 1000
          else 8000 * 1000
        }
    }
    def score(pairs: Combination): Score = pairs.foldLeft(0) {
      case (s, p) => s + pairingScore(p)
    }

    def nextCombos(combo: Combination): List[Combination] =
      players.filterNot { p =>
        combo.exists(c => c._1 == p || c._2 == p)
      } match {
        case a :: rest => rest.map { b =>
          (a, b) :: combo
        }
        case _ => Nil
      }

    sealed trait FindBetter
    case class Found(best: Combination) extends FindBetter
    case object End extends FindBetter
    case object NoBetter extends FindBetter

    def findBetter(from: Combination, than: Score): FindBetter =
      nextCombos(from) match {
        case Nil => End
        case nexts => nexts.foldLeft(none[Combination]) {
          case (current, next) =>
            val toBeat = current.fold(than)(score)
            if (score(next) >= toBeat) current
            else findBetter(next, toBeat) match {
              case Found(b) => b.some
              case End      => next.some
              case NoBetter => current
            }
        } match {
          case Some(best) => Found(best)
          case None       => NoBetter
        }
      }

    (players match {
      case x if x.size < 2 => Nil
      case List(p1, p2) if onlyTwoActivePlayers => List(p1.player -> p2.player)
      case List(p1, p2) if justPlayedTogether(p1.player.userId, p2.player.userId) => Nil
      case List(p1, p2) => List(p1.player -> p2.player)
      case ps => findBetter(Nil, Int.MaxValue) match {
        case Found(best) => best map {
          case (rp0, rp1) => rp0.player -> rp1.player
        }
        case _ =>
          logwarn("Could not make smart pairings for arena tournament")
          players map (_.player) grouped 2 collect {
            case List(p1, p2) => (p1, p2)
          } toList
      }
    }) map {
      Pairing.prep(tour, _)
    }
  }
}
