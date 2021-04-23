package com.cespinner
import cats.effect._
import cats.implicits._
import scala.concurrent.duration._
import com.cespinner.Spinnner._
import scala.util.Random

object IOSpinner extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    for {
      _ <- IO.println("Starting up some spinners!")
      t <- (1 to 10).toList.map((_, Random.nextInt(10))).traverse {
        case (idx, rnd) => IO.cede >> IO.sleep(rnd.seconds).withSpinner(
          s"[${idx}] running for ${rnd} seconds (Thread: ${Thread.currentThread.getName})",
            onEnd=s"[${idx}] finished"
          ).start
        }
        _ <-IO.sleep(10.seconds)

    } yield (ExitCode.Success)
  }
}
