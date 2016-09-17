/*
 * Copyright 2016 Heiko Seeberger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.heikoseeberger.gabbler.user

import akka.actor.Status.Failure
import akka.actor.{ Actor, ActorLogging, ActorRef, Props }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.{ Conflict, Created, NoContent, NotFound }
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.{ Directives, Route }
import akka.pattern.{ ask, pipe }
import akka.stream.ActorMaterializer
import akka.util.Timeout
import de.heikoseeberger.akkahttpcirce.CirceSupport
import java.net.InetSocketAddress
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object UserApi {

  final val Name = "user-api"

  def apply(address: String,
            port: Int,
            userRepository: ActorRef,
            userRepositoryTimeout: FiniteDuration): Props =
    Props(new UserApi(address, port, userRepository)(userRepositoryTimeout))

  def route(userRepository: ActorRef)(implicit userRepositoryTimeout: Timeout,
                                      ec: ExecutionContext): Route = {
    import CirceSupport._
    import Directives._
    import UserRepository._
    import io.circe.generic.auto._

    pathPrefix("users") {
      pathEnd {
        get {
          complete((userRepository ? GetUsers).mapTo[Users].map(_.users))
        } ~
        post {
          entity(as[AddUser]) { addUser =>
            extractUri { uri =>
              def location(user: User) = Location(uri.withPath(uri.path / user.id.toString))
              onSuccess(userRepository ? addUser) {
                case UserAdded(user)         => complete(Created, List(location(user)), user)
                case UsernameTaken(username) => complete(Conflict, s"Username $username taken!")
              }
            }
          }
        }
      } ~
      path(LongNumber) { id =>
        delete {
          onSuccess(userRepository ? RemoveUser(id)) {
            case UserRemoved(_) => complete(NoContent)
            case IdUnknown(_)   => complete(NotFound, s"User with id $id not found!")
          }
        }
      }
    }
  }
}

final class UserApi(address: String, port: Int, userRepository: ActorRef)(
    implicit userRepositoryTimeout: Timeout
) extends Actor
    with ActorLogging {
  import context.dispatcher

  private implicit val mat = ActorMaterializer()

  Http(context.system).bindAndHandle(UserApi.route(userRepository), address, port).pipeTo(self)

  override def receive = {
    case Http.ServerBinding(address) => handleBinding(address)
    case Failure(cause)              => handleBindFailure(cause)
  }

  private def handleBinding(address: InetSocketAddress) = {
    log.info("Listening on {}", address)
    context.become(Actor.emptyBehavior)
  }

  private def handleBindFailure(cause: Throwable) = {
    log.error(cause, "Can't bind to {}:{}!", address, port)
    context.stop(self)
  }
}
