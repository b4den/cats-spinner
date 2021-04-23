package com.cespinner

import cats.effect._
import cats.implicits._
import scala.concurrent.duration._
import scala.collection.mutable.Map
import cats.effect.std.{Console, Queue}

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

object Spinner {
  import Colors._
  val stateMapRef = Ref[IO].of(Map[String, State[IO]]())

  def spinner[F[_]: Async](queue: Queue[F, String], mapR:Ref[F, Map[String, State[F]]]) : F[Unit] = {
    val charStream = collection.immutable.Stream.continually("""⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏""".toStream).flatten.iterator
    /* Up one line, clear to EOL */
    val startLine = "\u001B[1A\u001B[K"

    def loopTheLoop[F[_]: Async](queue: Queue[F, String],  mapR:Ref[F, Map[String, State[F]]]): F[Unit] = for {
      messageMap <- mapR.get
      freshMessages <- messageMap.values.filter(_.status == Started).pure[F]
      nextChar = charStream.next()

      messages <- messageMap.toList.sortBy((s) => s._2.time).traverse{
       case (_, State(_, _, message, Started)) => message.get.flatMap(m => s"${nextChar} ${m} started".pure[F])
       case (_, State(_, _, message, Running)) => message.get.flatMap(m => s"${nextChar} ${m}".pure[F])
       case (_, State(_, _, message, Stopped)) => message.get.flatMap(m => s"$ANSI_GREEN✔$ANSI_RESET ${m}".pure[F])
       case (_, State(_, _, message, Failed)) =>  message.get.flatMap(m => s"$ANSI_RED-►$ANSI_RESET ${m}".pure[F])
      }
      _ <- queue.tryOffer(List.fill(messages.size)(startLine).mkString("") + messages.mkString("\n"))
      _ <- freshMessages.map(s => messageMap.update(s.id, State(s.id, s.time, s.message, Running))).pure[F]
      _ <- Async[F].sleep(50.millis)
      _ <- loopTheLoop(queue, mapR)
    } yield()

     loopTheLoop(queue, mapR)
  }

  def cancelFiber[F[_], A](fib: FiberIO[Unit], consumer: FiberIO[Unit], mapR:Ref[IO, Map[String, State[F]]], spinnerState: (String, State[F])): IO[Unit] = for {
      messageMap <- mapR.get
      (token, state) = spinnerState
      _ <-  messageMap.update(token, State(token, state.time, state.message, Stopped)).pure[IO]
      /* Lets ensure we have given our producer/consumer enough time to grab the messageRef and update. This is onnly really a
       * concern for the last State element provided to `withSpinner`:
       * For the curious, we could wrap these cancellations in an async `blocking` function but this wouldn't make sense for
       * ScalaJS */
      _ <- IO.sleep(50.millis)
      _ <- IO.both(fib.cancel, consumer.cancel)
    } yield ()

  def onExitMessage[F[_]: Async, A](successMessage: String, callback: Option[(A) => String] = None, res: A, mapR:Ref[F, Map[String, State[F]]], spinnerState: (String, State[F])): F[Unit] = for {
      messageMap <- mapR.get
      (token, state) = spinnerState
      message <- Ref[F].of(callback.map(cb => cb(res)).getOrElse(successMessage))
      _ <-  messageMap.update(token, State(token, state.time, message, Stopped)).pure[F]
    } yield ()

  def onFailureMessage[F[_]: Async](failureMessage: String, callback: Option[(Throwable) => String] = None, error: Throwable, mapR:Ref[F, Map[String, State[F]]], spinnerState: (String, State[F])): F[Unit] =  for {
      messageMap <- mapR.get
      (token, state) = spinnerState
      message <- Ref[F].of(callback.map(cb => cb(error)).getOrElse(failureMessage))
      _ <-  messageMap.update(token, State(token, state.time, message, Failed)).pure[F]
    } yield ()

  def spin[F[_]: Async: Console](messageQ: Queue[F, String]): F[Unit] = {
    def loop[F[_]: Async: Console](messageQ: Queue[F, String]): F[Unit] = for {
      message <- messageQ.take
      _ <- Console[F].println(s"$message") >> loop(messageQ)
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
      withMessageRef: Ref[IO, String] = Ref.unsafe("")): IO[A] = for {
        _ <- withMessageRef.update(x => if (x.isEmpty) onStart else x)
        messageQ  <- qBounded
        stateMap  <- stateMapRef
        token <- Unique[IO].unique.map(_.toString)
        time <- IO.realTime
        spinnerState = token -> State(token, time, withMessageRef, Started)
        _ <- stateMap.modify(map => ( map += spinnerState, map))
        _ <- messageQ.offer("")
        consumer <- spin(messageQ).start
        res <- IO.bracketFull(_ => spinner(messageQ, stateMap).start)(_ => ioa)((spinnerFib, _) => cancelFiber(spinnerFib, consumer, stateMap, spinnerState))
          .attempt.flatMap({
            case Right(result) => onExitMessage(onEnd, onSuccessCallback, result, stateMap, spinnerState) >> IO(result)
            case Left(error) => onFailureMessage(onFail, callback = onErrorCallback, error = error, stateMap, spinnerState) >> IO.raiseError(error)
          })
    } yield res
  }
}

