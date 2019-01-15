package com.softwaremill.sttp.playwssttandalone

import akka.stream.Materializer
import akka.stream.scaladsl.{FileIO, Source}

import akka.stream.scaladsl.StreamConverters
import akka.util.ByteString
import java.io.{File, IOException}

import com.softwaremill.sttp._
import com.softwaremill.sttp.internal._

import play.api.libs.ws._

import scala.concurrent.{ExecutionContext, Future}

class PlayWSClientBackend(wsClient: StandaloneWSClient, mustCloseClient: Boolean)(implicit ec: ExecutionContext,
                                                                                  mat: Materializer)
    extends SttpBackend[Future, Source[ByteString, Any]] {

  private type S = Source[ByteString, Any]
  import DefaultBodyWritables.writeableOf_WsBody

  private def convertRequest[T](request: Request[T, S]): StandaloneWSRequest = {
    val holder = wsClient.url(request.uri.toString())
    val maybeBody = requestBodyToWsBody(request.body)

    maybeBody
      .foldLeft(holder)(_.withBody(_))
      .withMethod(request.method.m)
      .withRequestTimeout(request.options.readTimeout)
      .withHttpHeaders(request.headers: _*)

  }

// ignore content type
  private def requestBodyToWsBody[T](requestBody: RequestBody[S]): Option[WSBody] = requestBody match {
    case StringBody(s, encoding, _) => Some(InMemoryBody(ByteString(s, encoding)))
    case ByteArrayBody(a, _)        => Some(InMemoryBody(ByteString(a)))
    case ByteBufferBody(b, _)       => Some(InMemoryBody(ByteString(b)))
    case InputStreamBody(in, _)     => Some(SourceBody(StreamConverters.fromInputStream(() => in)))
    case StreamBody(s: S)           => Some(SourceBody(s))
    case NoBody                     => None
    case b                          => throw new IllegalArgumentException(s"$b bbody is not supported")
  }

  def send[T](r: Request[T, S]): Future[Response[T]] = {
    val request = convertRequest(r)

    request.execute().flatMap(readResponse(_, r.options.parseResponseIf, r.response))
  }

  private def readResponse[T](response: StandaloneWSResponse,
                              parseIfCondition: (StatusCode) => Boolean,
                              responseAs: ResponseAs[T, S]) = {

    val headers = response.headers.toList.flatMap {
      case (name, values) => values.map((name, _))
    }

    val metadata = ResponseMetadata(headers, response.status, response.statusText)

    val body =
      if (parseIfCondition(response.status))
        readBody(response, metadata, responseAs).map(Right.apply _)
      else readBody(response, metadata, ResponseAsByteArray).map(Left.apply _)
    body.map(b => Response(b, metadata.code, metadata.statusText, metadata.headers, Nil))
  }

  private def readBody[T](response: StandaloneWSResponse,
                          metadata: ResponseMetadata,
                          responseAs: ResponseAs[T, S]): Future[T] = responseAs match {
    case MappedResponseAs(raw, g) =>
      readBody(response, metadata, raw)
        .map(r => g(r, metadata))
    case ResponseAsString(encoding) =>
      Future {
        response.bodyAsBytes.decodeString(
          metadata.header(HeaderNames.ContentType).flatMap(encodingFromContentType).getOrElse(encoding))
      }
    case ResponseAsByteArray => Future { response.bodyAsBytes.toArray }
    case r @ ResponseAsStream() =>
      Future.successful(r.responseIsStream(response.bodyAsSource))
    case ResponseAsFile(file, overwrite) =>
      saveFile(new File(file.name), overwrite, response).map(_ => file)
    case IgnoreResponse => Future { response.bodyAsBytes; () }

  }

  def close(): Unit =
    if (mustCloseClient)
      wsClient.close()

  private def saveFile(file: File, overwrite: Boolean, response: StandaloneWSResponse) = {

    if (!file.exists()) {
      file.getParentFile.mkdirs()
      file.createNewFile()
    } else if (!overwrite) {
      Future.failed(new IOException(s"File ${file.getAbsolutePath} exists - overwriting prohibited"))
    }

    response.bodyAsSource.runWith(FileIO.toPath(file.toPath))
  }

  override val responseMonad: MonadError[Future] = new FutureMonad
}

object PlayWSClientBackend {
  def apply(client: StandaloneWSClient)(implicit ec: ExecutionContext, mat: Materializer): PlayWSClientBackend =
    new PlayWSClientBackend(client, false)
}
