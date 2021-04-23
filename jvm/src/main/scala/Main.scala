package com.cespinner
import cats.effect._
import cats.implicits._
import scala.concurrent.duration._
import com.cespinner.Spinner._
import com.cespinner.Colors._
import scala.util.Random
import cats.effect.std.CountDownLatch

object IOSpinner extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    for {
      _ <- runExample
    } yield(ExitCode.Success)
  }

  def runExample(): IO[Unit] = for {
    _ <- IO.println("Starting work in Scala land")
    l <- CountDownLatch[IO](10)
    _ <- (1 to 10).toList.map((_, Random.nextInt(10))).traverse {
      case (idx, rnd) => IO.cede >> ( IO.sleep(rnd.seconds).withSpinner(
        s"[${idx}] running for ${rnd} seconds (Thread: ${Thread.currentThread.getName})",
        onEnd=s"[${idx}] finished"
      ) *> l.release).start
    }
    _ <- l.await >> IO.unit.withSpinner("Finished countdown") >> IO.sleep(100.millis) >> IO.println("Scala version done!")
  } yield()
}
