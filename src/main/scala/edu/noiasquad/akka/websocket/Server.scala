package edu.noiasquad.akka.websocket

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.typed.scaladsl.ActorSource

import scala.concurrent.ExecutionContext
import scala.io.StdIn

object Server extends App {

  val rootBehavior = Behaviors.setup[Nothing] { context =>

    implicit val classicActorSystem: akka.actor.ActorSystem = context.system.toClassic
    implicit val executionContext: ExecutionContext = context.system.executionContext

    val echoService: Flow[Message, Message, _] = Flow[Message].map {
      case TextMessage.Strict(txt) => TextMessage("ECHO: " + txt)
      case _ => TextMessage("Message type unsupported")
    }

    val numberSource = Source(1 to 1000).map(i => TextMessage(i.toString))

    sealed trait Protocol
    case class Do(i: Int) extends Protocol
    case object Complete extends Protocol
    case class Fail(ex: Exception) extends Protocol

    val actorSource: Source[TextMessage.Strict, ActorRef[Protocol]] =
      ActorSource.actorRef[Protocol](
        { case Complete => },
        { case Fail(ex) => ex },
        bufferSize = 0,
        overflowStrategy = OverflowStrategy.dropHead)

        .map {
          case Do(i) => TextMessage.Strict(i.toString)
        }

    val actorRef: ActorRef[Protocol] = actorSource
      .to(Sink.foreach(println))
      .run()

    val interface = "localhost"
    val port = 8080

    val route =
      pathEndOrSingleSlash {
        get {
          complete("Welcome to websocket server")
        }
      } ~ path("ws-echo") {
        extractUpgradeToWebSocket { upgrade =>
          complete(upgrade.handleMessages(echoService))
        }
      } ~ path("ws-push") {
        extractUpgradeToWebSocket { upgrade =>
          complete(upgrade.handleMessagesWithSinkSource(Sink.ignore, numberSource))
        }
      } ~ path("ws-push-actor") {
        extractUpgradeToWebSocket { upgrade =>
          complete(upgrade.handleMessagesWithSinkSource(Sink.ignore, actorSource))
        }
      } ~ path("send-42") {
        get {
          actorRef ! Do(42)
          complete("42 has been sent to the actor, and it should have been pushed to the websocket channel")
        }
      }

    val binding = Http().bindAndHandle(route, interface, port)
    println(s"Server is now online at http://$interface:$port\nPress RETURN to stop...")
    StdIn.readLine()

    binding.flatMap(_.unbind()).onComplete(_ => classicActorSystem.terminate())
    println("Server is down...")

    Behaviors.empty
  }
  ActorSystem[Nothing](rootBehavior, "UserGuardian")
}
