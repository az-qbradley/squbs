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

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpHeader, HttpRequest, HttpResponse}

case class ErrorLog(error: Throwable)

case class RequestContext(request: HttpRequest,
                          response: Option[HttpResponse] = None,
                          attributes: Map[String, Any] = Map.empty,
                          error: Option[ErrorLog] = None) {

  def withAttributes(attributes: (String, Any)*): RequestContext = {
    this.copy(attributes = this.attributes ++ attributes)
  }

  def attribute[T](key: String): Option[T] = {
    attributes.get(key) match {
      case None => None
      case Some(null) => None
      case Some(value) => Some(value.asInstanceOf[T])
    }
  }

  def addRequestHeaders(headers: HttpHeader*): RequestContext = {
    copy(request = request.copy(headers = request.headers ++ headers))
  }

  def addResponseHeaders(headers: HttpHeader*): RequestContext = {
    response.fold(this) {
      resp => copy(response = Option(resp.copy(headers = request.headers ++ headers)))
    }
  }
}

object RequestContext {
  implicit def attributes2Headers(attributes: Map[String, Any]): Seq[HttpHeader] = {
    attributes.toSeq.map {
      attr => RawHeader(attr._1, String.valueOf(attr._2))
    }
  }
}
