package net.wadon

import cats.Functor
import cats.effect.{ExitCode, IO, IOApp}
import cats.implicits._
import higherkindness.droste._
import higherkindness.droste.data._
import higherkindness.droste.macros.deriveTraverse

import scala.util.Random

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {

    import Scheme._
    import RecursiveScheme._

    for {
      _ <- IO.delay {
        println("START")
      }
      storedSnapshot <- getStoredSnapshot("f")
      result <- snap.apply(storedSnapshot)
      _ <- IO.delay {
        println(result)
      }
      _ <- IO.delay {
        println("-------------")
      }
      balances <- bal.apply(result)
      _ <- IO.delay {
        println(balances)
      }
      _ <- IO.delay {
        println("-------------")
      }
      hylo <- balHylo.apply(storedSnapshot)
      _ <- IO.delay {
        println(hylo)
      }
    } yield ExitCode.Success
  }

}

object Scheme {
  type Hash = String
  type Address = String

  case class Id(hash: Hash) {
    def toInt: Int = {
      hash match {
        case "a" => 0
        case "b" => 1
        case "c" => 2
        case "d" => 3
        case "e" => 4
        case _   => Random.nextInt()
      }
    }
  }

  case class Transaction(src: Address, dst: Address, amount: Long)
  case class Observation(id: Id)

  case class CheckpointBlock(transactions: Seq[Transaction],
                             observations: Seq[Observation]) {
    val hash = Random.nextString(5)
  }

  case class Snapshot(previousHash: Hash, blocks: Seq[Hash])
  case class StoredSnapshot(snapshot: Snapshot, blocks: Seq[CheckpointBlock])

  case class Experience(id: Int, outcome: Double)

  object Experience {
    def apply(id: Id, observations: Seq[Observation]): Experience = {
      val bestExperience = 1.0
      val negativeExperiences = observations.map(_ => -0.1d).sum
      val outcome = bestExperience + negativeExperiences
      Experience(id.toInt, if (outcome > 0) outcome else 0.0)
    }
  }

  val addresses = Array("aaa", "bbb", "ccc", "ddd", "eee", "fff")

  def getRandomBlock: CheckpointBlock = {
    val transactions = Seq.fill(5) {
      Transaction(
        addresses(Random.nextInt(addresses.length - 1)),
        addresses(Random.nextInt(addresses.length - 1)),
        Random.nextLong()
      )
    }

    val observations = Seq.fill(5) {
      val ids = Seq("a", "b", "c", "d", "e").map(Id)
      val shuffled = Random.shuffle(ids)

      Observation(shuffled.head)
    }

    CheckpointBlock(transactions, observations)
  }

  val storedSnapshots: Map[String, StoredSnapshot] =
    Map(
      "a" -> StoredSnapshot(
        Snapshot("", Seq.empty),
        Seq.fill(5)(getRandomBlock)
      ),
      "b" -> StoredSnapshot(
        Snapshot("a", Seq.empty),
        Seq.fill(5)(getRandomBlock)
      ),
      "c" -> StoredSnapshot(
        Snapshot("b", Seq.empty),
        Seq.fill(5)(getRandomBlock)
      ),
      "d" -> StoredSnapshot(
        Snapshot("c", Seq.empty),
        Seq.fill(5)(getRandomBlock)
      ),
      "e" -> StoredSnapshot(
        Snapshot("d", Seq.empty),
        Seq.fill(5)(getRandomBlock)
      ),
      "f" -> StoredSnapshot(
        Snapshot("e", Seq.empty),
        Seq.fill(5)(getRandomBlock)
      )
    )

  def getStoredSnapshot(hash: Hash): IO[StoredSnapshot] = {
    IO.delay {
      storedSnapshots(hash)
    }
  }
}

object RecursiveScheme {
  import Scheme._

  private def spend(transactions: Seq[Transaction]): Map[Address, Long] =
    transactions.groupBy(_.src).mapValues(-_.map(_.amount).sum)

  private def received(transactions: Seq[Transaction]): Map[Address, Long] =
    transactions.groupBy(_.dst).mapValues(_.map(_.amount).sum)

  case class SnapshotOutput(balances: Map[Address, Long],
                            experiences: List[Experience])

  object SnapshotOutput {
    def apply(snapshot: StoredSnapshot): SnapshotOutput = {
      val balances = snapshot.blocks
        .map(_.transactions)
        .foldRight(Map.empty[Address, Long])(
          (txs, acc) => (spend(txs) |+| received(txs)) |+| acc
        )

      val experiences =
        snapshot.blocks
          .flatMap(_.observations)
          .groupBy(_.id)
          .transform {
            case (id, obs) => Experience(id, obs)
          }
          .values
          .toList

      new SnapshotOutput(balances, experiences)
    }
  }

  @deriveTraverse sealed trait Stack[A]

  object Stack {
    implicit val stackFunctor: Functor[Stack] = new Functor[Stack] {
      override def map[A, B](sa: Stack[A])(f: A => B): Stack[B] =
        sa match {
          case Stack.Done(result)  => Stack.Done(result)
          case Stack.Prev(a, next) => Stack.Prev(f(a), next)
        }
    }

    case class Done[A](result: SnapshotOutput) extends Stack[A]
    case class Prev[A](a: A, prev: SnapshotOutput) extends Stack[A]
  }

  // Hash --get--> Snapshot ---deserialize--> StoredSnapshot --> SnapshotOutput
  val snapCoalgebra: CoalgebraM[IO, Stack, StoredSnapshot] =
    CoalgebraM(storedSnapshot => {
      val prevHash = storedSnapshot.snapshot.previousHash
      if (prevHash == "") {
        Stack.Done[StoredSnapshot](SnapshotOutput(storedSnapshot)).pure[IO]
      } else {
        getStoredSnapshot(prevHash).map(
          Stack.Prev(_, SnapshotOutput(storedSnapshot))
        )
      }
    })

  val snap = scheme.anaM(snapCoalgebra)

  val balancesAlgebra: AlgebraM[IO, Stack, Map[Address, Long]] =
    AlgebraM {
      case Stack.Done(output) => output.balances.pure[IO]
      case Stack.Prev(balances, output) =>
        IO {
          balances |+| output.balances
        }
    }

  val bal = scheme.cataM(balancesAlgebra)

  val balHylo = scheme.hyloM(balancesAlgebra, snapCoalgebra)

}
