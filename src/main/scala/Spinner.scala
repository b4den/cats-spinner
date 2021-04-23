package com.cespinner

import cats.effect._
import cats.implicits._
import scala.concurrent.duration._
import cats.effect.std.Queue
import scala.collection.mutable.Map

object Colors {
  final val ANSI_RESET = "\u001B[0m";
  final val ANSI_BLACK = "\u001B[30m";
  final val ANSI_RED = "\u001B[31m";
  final val ANSI_GREEN = "\u001B[32m";
  final val ANSI_YELLOW = "\u001B[33m";
  final val ANSI_BLUE = "\u001B[34m";
  final val ANSI_PURPLE = "\u001B[35m";
  final val ANSI_CYAN = "\u001B[36m";
  final val ANSI_WHITE = "\u001B[37m";
}

sealed trait Status
case object Running extends Status
case object Stopped extends Status
case object Started extends Status
case object Failed extends Status

case class State[F[_]](id: String, time: FiniteDuration, message: Ref[F, String], status: Status)

object Spinnner {
  import Colors._
  val stateMapRef = Ref[IO].of(Map[String, State[IO]]())

  def spinner[F[_]: Async: Spawn](queue: Queue[F, String], mapR:Ref[F, Map[String, State[F]]]) : F[Unit] = {
    val charStream = collection.immutable.Stream.continually("""⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏""".toStream).flatten.iterator
    val startLine = "\u001B[1A\u001B[K"

    def loopTheLoop[F[_]: Async](queue: Queue[F, String],  mapR:Ref[F, Map[String, State[F]]]): F[Unit] = for {
      messageMap <- mapR.get
      freshMessages <- messageMap.values.filter(_.status == Started).pure[F]
      nextChar = charStream.next()

      messages <- messageMap.toList.sortBy((s) => s._2.time).traverse{
       case (id, State(_, _, message, Started)) => message.get.flatMap(m => s"${nextChar} ${m} started".pure[F])
       case (id, State(_, _, message, Running)) => message.get.flatMap(m => s"${nextChar} ${m}".pure[F])
       case (id, State(_, _, message, Stopped)) => message.get.flatMap(m => s"$ANSI_GREEN✔$ANSI_RESET ${m}".pure[F])
       case (id, State(_, _, message, Failed)) =>  message.get.flatMap(m => s"$ANSI_RED-►$ANSI_RESET ${m}".pure[F])
     }
     _ <- queue.tryOffer(List.fill(messages.size)(startLine).mkString("") + messages.mkString("\n"))
      _ <- freshMessages.map(s => messageMap.update(s.id, State(s.id, s.time, s.message, Running))).pure[F]
      _ <- Async[F].sleep(50.millis)
      _ <- loopTheLoop(queue, mapR)
    } yield()

     loopTheLoop(queue, mapR)
  }

  def cancelFiber[F[_], A](fib: FiberIO[Unit], mapR:Ref[IO, Map[String, State[F]]], spinnerState: (String, State[F])): IO[Unit] = for {
      messageMap <- mapR.get
      (token, state) = spinnerState
      _ <-  messageMap.update(token, State(token, state.time, state.message, Stopped)).pure[IO]
      _ <- IO.sleep(50.millis)
      _ <- fib.cancel
    } yield ()

  /* We should track the number of producers here before offering */
  def onExitMessage[F[_]: Async, A](successMessage: String, callback: Option[(A) => String] = None, res: A, mapR:Ref[F, Map[String, State[F]]], spinnerState: (String, State[F])): F[Unit] = {
    for {
      messageMap <- mapR.get
      (token, state) = spinnerState
      message <- Ref[F].of(callback.map(cb => cb(res)).getOrElse(successMessage))
      _ <-  messageMap.update(token, State(token, state.time, message, Stopped)).pure[F]
    } yield ()
  }

  def onFailureMessage[F[_]: Async](failureMessage: String, callback: Option[(Throwable) => String] = None, error: Throwable, mapR:Ref[F, Map[String, State[F]]], spinnerState: (String, State[F])): F[Unit] = {
    for {
      messageMap <- mapR.get
      (token, state) = spinnerState
      message <- Ref[F].of(callback.map(cb => cb(error)).getOrElse(failureMessage))
      _ <-  messageMap.update(token, State(token, state.time, message, Failed)).pure[F]
      _ <- Async[F].sleep(1.second)
    } yield ()
    //queue.offer(s"► ${callback.map(cb => cb(error)).getOrElse(failureMessage)} ${List.fill(clearLength)(" ").mkString("")}")
  }

  def spin(messageQ: Queue[IO, String]): IO[Unit] = {
    def loop(messageQ: Queue[IO, String]): IO[Unit] = for {
        message <- messageQ.take
        _ <- IO.println(s"$message") >> loop(messageQ)
      } yield ()
    loop(messageQ)
  }

  implicit class SpinnerOp[A](ioa: IO[A]) {
  val qBounded = Queue.bounded[IO, String](1)

    def withSpinner(
      onStart: String = "Starting",
      onEnd: String = "Finished!",
      onFail: String = "Failed..",
      onSuccessCallback: Option[(A) => String] = None,
      onErrorCallback: Option[(Throwable) => String] = None,
      withMessageRef: Ref[IO, String] = Ref.unsafe(""),
      //withMessageRef: Option[Ref[IO, String]] = None,
      ): IO[A] = {

      for {
        /* We could use IO[Ref[IO, String]] here but we loose the context because we need to run this in a F[_] effect */
        _ <- withMessageRef.update(x => if (x.isEmpty) onStart else x)
        messageQ  <- qBounded
        stateMap  <- stateMapRef
        token <- Unique[IO].unique.flatMap(_.toString.pure[IO])
        time <- IO.realTime
        spinnerState = token -> State(token, time, withMessageRef, Started)
        _ <- stateMap.modify(map => ( map += spinnerState, map))
        _ <- messageQ.offer("")
        fib  <- spin(messageQ).start
        res <- IO.bracketFull(_ => spinner(messageQ, stateMap).start)(_ => ioa)((spinnerFib, _) => cancelFiber(spinnerFib, stateMap, spinnerState))
                .attempt.flatMap({
                  case Right(result) => onExitMessage(onEnd, onSuccessCallback, result, stateMap, spinnerState) >> IO(result)
                  case Left(error) => onFailureMessage(onFail, callback = onErrorCallback, error = error, stateMap, spinnerState) >> IO.raiseError(error)
                  })
      } yield res
    }
  }
}

