/*
 *  Copyright 2015 PayPal
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.squbs.stream.httpclient

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.stream.scaladsl.Flow
import org.squbs.httpclient.HttpClientEndpointNotExistException
import org.squbs.httpclient.endpoint.EndpointRegistry
import org.squbs.httpclient.env.{Default, Environment, EnvironmentRegistry}

import scala.concurrent.Future
import scala.language.postfixOps

object HttpClientFactory {
  // Connection-Level
  def connectionFlow(name: String, env: Environment = Default)(implicit system: ActorSystem): Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] = {
    Http().outgoingConnection(endpoint(name, env).uri.toString())
  }

  def endpoint(name: String, env: Environment = Default)(implicit system: ActorSystem) = {
    val newEnv = env match {
      case Default => EnvironmentRegistry(system).resolve(name)
      case _ => env
    }
    EndpointRegistry(system).resolve(name, env) getOrElse {
      throw HttpClientEndpointNotExistException(name, env)
    }
  }

  // Host-Level
  def poolClientFlow[T](name: String, env: Environment = Default)(implicit system: ActorSystem) = Http().cachedHostConnectionPool[T](endpoint(name, env).uri.toString())

  // Request-Level
  def http(implicit system: ActorSystem) = Http()
}