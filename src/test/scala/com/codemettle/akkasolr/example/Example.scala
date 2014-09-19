/*
 * Example.scala
 *
 * Updated: Sep 19, 2014
 *
 * Copyright (c) 2014, CodeMettle
 */
package com.codemettle.akkasolr.example

import com.typesafe.config.Config
import net.ceedubs.ficus.Ficus._
import org.apache.solr.client.solrj.SolrQuery

import com.codemettle.akkasolr.Solr

import akka.actor._
import scala.concurrent.duration._

/**
 * @author steven
 *
 */
object Example extends App {
    val system = ActorSystem("Example")

    val main = system.actorOf(Props[MyAct])
    system.actorOf(Props[WD])

    private class MyAct extends Actor with ActorLogging {
        private var conn: ActorRef = _

        private val config = context.system.settings.config.as[Config]("example")

        override def preStart() = {
            super.preStart()

            import context.dispatcher

            context.system.scheduler.scheduleOnce(35.seconds, self, 'q)
            context.system.scheduler.scheduleOnce(2.minutes, self, PoisonPill)

            Solr.Client.clientTo(config.as[String]("solrAddr"))
        }

        override def postStop() = {
            super.postStop()

            log info "stopping"
        }

        private def sendQuery() = {
            conn ! Solr.Select(new SolrQuery(config.as[String]("testQuery")))
        }

        def receive = {
            case Solr.SolrConnection(a, c) ⇒
                log.info("Got {} for {} from {}", c, a, sender())
                conn = sender()
                sendQuery()

            case 'q ⇒ sendQuery()

            case m ⇒
                log.info("got {}", m)
        }
    }

    private class WD extends Actor {
        context watch main
        def receive = {
            case Terminated(`main`) ⇒ system.shutdown()
        }
    }
}