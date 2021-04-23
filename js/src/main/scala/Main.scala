package com.cespinner
import cats.effect._
import cats.implicits._
import scala.concurrent.duration._
import com.cespinner.Spinner._
import com.cespinner.Colors._
import scala.util.Random

object IOSpinner extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    runExample.as(ExitCode.Success)
      }

  def runExample(): IO[Unit] = for {
    _ <- IO.println(s"\u001B[1;41mcats-spinner in ScalaJS land$ANSI_RESET")
    fibs <- (1 to 10).toList.map((_, Random.nextInt(10))).traverse {
      case (idx, rnd) => IO.sleep(rnd.seconds).withSpinner(
        s"[${idx}] running for ${rnd} seconds (Thread: ${Thread.currentThread.getName})",
        onEnd=s"[${idx}] finished"
      ).start
    }
    _ <- fibs.traverse(_.join)
    _ <- IO.unit.withSpinner("Finished countdown") >> IO.sleep(100.millis) >> IO.println("ScalaJS/ Node version done!")
  } yield()
}
