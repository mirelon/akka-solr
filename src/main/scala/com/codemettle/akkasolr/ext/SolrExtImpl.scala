/*
 * SolrExtImpl.scala
 *
 * Updated: Oct 16, 2014
 *
 * Copyright (c) 2014, CodeMettle
 */
package com.codemettle.akkasolr.ext

import spray.http.Uri

import com.codemettle.akkasolr.Solr
import com.codemettle.akkasolr.ext.SolrExtImpl.zkRe
import com.codemettle.akkasolr.imperative.ImperativeWrapper
import com.codemettle.akkasolr.manager.Manager
import com.codemettle.akkasolr.util.Util

import akka.ConfigurationException
import akka.actor.{ActorRef, ExtendedActorSystem, Extension}
import akka.pattern.AskTimeoutException
import akka.util.Timeout
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * @author steven
 *
 */
object SolrExtImpl {
    private val zkRe = """zk://(([^,]+:\d+[,]?)+)""".r
}

class SolrExtImpl(eas: ExtendedActorSystem) extends Extension {
    val config = eas.settings.config getConfig "akkasolr"

    val manager = eas.actorOf(Manager.props, "Solr")

    val responseParserDispatcher = eas.dispatchers lookup "akkasolr.response-parser-dispatcher"

    lazy val zookeeperDispatcher = eas.dispatchers lookup "akkasolr.zookeeper-dispatcher"

    val maxBooleanClauses = config getInt "solrMaxBooleanClauses"

    val maxChunkSize = {
        val size = config getBytes "sprayMaxChunkSize"
        if (size > Int.MaxValue || size < 0) sys.error("Invalid maxChunkSize")
        size.toInt
    }

    val connectionProvider = {
        val fqcn = config getString "connectionProvider"
        eas.dynamicAccess.createInstanceFor[ConnectionProvider](fqcn, Nil) match {
            case Success(cp) ⇒ cp
            case Failure(e) ⇒
                throw new ConfigurationException(s"Could not find/load Connection Provider class [$fqcn]", e)
        }
    }

    def connectionActorProps(uri: Uri) = connectionProvider.connectionActorProps(uri, eas)

    private def managerMessageForUrl(solrUrl: String) = {
        zkRe findFirstIn solrUrl match {
            case None ⇒ Manager.Messages.ClientTo(Util normalize solrUrl, solrUrl)
            case Some(_) ⇒
                // not letting the user customize the options...they could send a SolrCloudClientTo message manually,
                // or maybe i'll add another method
                Manager.Messages.SolrCloudClientTo(solrUrl.replaceAllLiterally("zk://", ""),
                    Solr.SolrCloudConnectionOptions(eas))
        }
    }

    /**
     * Request a Solr connection actor. A connection will be created if needed.
     *
     * === Example ===
     * {{{
     *     override def preStart() = {
     *       super.preStart()
     *
     *       Solr.Client.clientTo("http://my-solr:8983/solr")
     *     }
     *
     *     override def receive = {
     *       case Solr.SolrConnection("http://my-solr:8983/solr", connectionActor) =>
     *           // connectionActor available for requests
     *     }
     * }}}
     *
     * A `solrUrl` of the form "zk://host:port,host:port,host:port" will create a
     * [[com.codemettle.akkasolr.client.SolrCloudConnection]]
     *
     * @param solrUrl Solr URL to connect to
     * @param requestor Actor to send resulting connection or errors to. Since it is implicit,
     *                  calling this method from inside an actor without specifying `requestor` will use the Actor's
     *                  implicit `self`
     * @return Unit; sends a [[Solr.SolrConnection]] message to `requestor`. A `spray.can.Http.ConnectionException`
     *         wrapped in a [[akka.actor.Status.Failure]] may be raised by Spray and sent to `requestor`.
     */
    def clientTo(solrUrl: String)(implicit requestor: ActorRef) = {
        manager.tell(managerMessageForUrl(solrUrl), requestor)
    }

    /**
     * `Ask`s the Solr.Client.manager for a connection actor.
     *
     * @see [[SolrExtImpl.clientTo]]
     * @return a Future containing the [[com.codemettle.akkasolr.client.ClientConnection]]'s [[ActorRef]]
     */
    def clientFutureTo(solrUrl: String)(implicit exeCtx: ExecutionContext): Future[ActorRef] = {
        import akka.pattern.ask
        import scala.concurrent.duration._
        implicit val timeout = Timeout(10.seconds)

        (manager ? managerMessageForUrl(solrUrl)).mapTo[Solr.SolrConnection] transform (_.connection, {
            case _: AskTimeoutException ⇒ new Exception("Unknown error, no response from Solr Manager")
            case t ⇒ t
        })
    }

    /**
     * Creates an [[ImperativeWrapper]], useful for transitioning from other Solr libraries
     *
     * @see [[clientFutureTo]]
     * @return a [[Future]] containing an [[com.codemettle.akkasolr.imperative.ImperativeWrapper]] around the
     *         akka-solr client connection
     */
    def imperativeClientTo(solrUrl: String)(implicit exeCtx: ExecutionContext): Future[ImperativeWrapper] = {
        clientFutureTo(solrUrl) map (a ⇒ ImperativeWrapper(a)(eas))
    }

    /**
     * Request a SolrCloud connection that behaves like a [[org.apache.solr.client.solrj.impl.CloudSolrServer]].
     * Just like regular connections, a cached connection will be returned if one already exists.
     *
     * @see [[SolrExtImpl.clientTo()]]
     * @param zkHost host string in the same format that CloudSolrServer expects ("host:port[,host:port,..]")
     * @param options options for the connection. Can be overridden globally; see "solrcloud-connection-defaults" in reference.conf
     * @param requestor Actor to send resulting connection to. Since it is implicit,
     *                  calling this method from inside an actor without specifying `requestor` will use the Actor's
     *                  implicit `self`
     * @return Unit; sends a [[Solr.SolrConnection]] message to `requestor`.
     */
    def solrCloudClientTo(zkHost: String,
                          options: Solr.SolrCloudConnectionOptions = Solr.SolrCloudConnectionOptions(eas))
                         (implicit requestor: ActorRef) = {
        manager.tell(Manager.Messages.SolrCloudClientTo(zkHost, options), requestor)
    }

    def solrCloudClientFutureTo(zkHost: String,
                                options: Solr.SolrCloudConnectionOptions = Solr.SolrCloudConnectionOptions(eas))
                               (implicit exeCtx: ExecutionContext): Future[ActorRef] = {
        import akka.pattern.ask
        import scala.concurrent.duration._
        implicit val timeout = Timeout(10.seconds)

        (manager ? Manager.Messages.SolrCloudClientTo(zkHost, options)).mapTo[Solr.SolrConnection] transform (_.connection, {
            case _: AskTimeoutException ⇒ new Exception("Unknown error, no response from Solr Manager")
            case t ⇒ t
        })
    }

    def solrCloudImperativeClientTo(zkHost: String,
                                    options: Solr.SolrCloudConnectionOptions = Solr.SolrCloudConnectionOptions(eas))
                                   (implicit exeCtx: ExecutionContext): Future[ImperativeWrapper] = {
        solrCloudClientFutureTo(zkHost, options) map (a ⇒ ImperativeWrapper(a)(eas))
    }

    // combines any urls that normalize to the same uri
    private def urlsToUriMap(solrUrls: Set[String]) = (Map.empty[Uri, String] /: solrUrls) {
        case (acc, solrUrl) ⇒ acc + ((Util normalize solrUrl) → solrUrl)
    }

    /**
     * Request a LoadBalanced connection that behaves like a [[org.apache.solr.client.solrj.impl.LBHttpSolrServer]].
     * Just like regular connections, a cached connection will be returned if one already exists.
     *
     * @see [[SolrExtImpl.clientTo()]]
     * @param solrUrls set of Solr connections to create. If any URLs in the list resolve to the same
     *                 [[Uri]] from [[Util.normalize( )]], only one connection will be created for each unique Uri.
     * @param options options for the LoadBalanced connection. Can be overridden globally; see
     *                "load-balanced-connection-defaults" in reference.conf
     * @param requestor Actor to send resulting connection to. Since it is implicit,
     *                  calling this method from inside an actor without specifying `requestor` will use the Actor's
     *                  implicit `self`
     * @return Unit; sends a [[Solr.SolrLBConnection]] message to `requestor`
     */
    def loadBalancedClientTo(solrUrls: Set[String], options: Solr.LBConnectionOptions = Solr.LBConnectionOptions(eas))
                            (implicit requestor: ActorRef) = {
        manager.tell(Manager.Messages.LBClientTo(urlsToUriMap(solrUrls), solrUrls, options), requestor)
    }

    def loadBalancedClientFutureTo(solrUrls: Set[String],
                                   options: Solr.LBConnectionOptions = Solr.LBConnectionOptions(eas))
                                  (implicit exeCtx: ExecutionContext): Future[ActorRef] = {
        import akka.pattern.ask
        import scala.concurrent.duration._
        implicit val timeout = Timeout(10.seconds)

        val msg = Manager.Messages.LBClientTo(urlsToUriMap(solrUrls), solrUrls, options)

        (manager ? msg).mapTo[Solr.SolrLBConnection] transform(_.connection, {
            case _: AskTimeoutException ⇒ new Exception("Unknown error, no response from Solr Manager")
            case t ⇒ t
        })
    }

    def loadBalancedImperativeClientTo(solrUrls: Set[String],
                                       options: Solr.LBConnectionOptions = Solr.LBConnectionOptions(eas))
                                      (implicit exeCtx: ExecutionContext): Future[ImperativeWrapper] = {
        loadBalancedClientFutureTo(solrUrls, options) map (a ⇒ ImperativeWrapper(a)(eas))
    }
}
