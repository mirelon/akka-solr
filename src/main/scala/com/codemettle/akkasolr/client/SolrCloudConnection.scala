/*
 * SolrCloudConnection.scala
 *
 * Updated: Oct 16, 2014
 *
 * Copyright (c) 2014, CodeMettle
 */
package com.codemettle.akkasolr.client

import org.apache.solr.common.cloud.ZkStateReader

import com.codemettle.akkasolr.Solr
import com.codemettle.akkasolr.client.SolrCloudConnection.{Connect, OperateOnCollection, fsm}
import com.codemettle.akkasolr.client.zk.ZkUtil
import com.codemettle.akkasolr.util.Util

import akka.actor._
import akka.pattern._
import scala.util.{Failure, Try}

/**
 * @author steven
 *
 */
object SolrCloudConnection {
    def props(lbConnection: ActorRef, zkHost: String, config: Solr.SolrCloudConnectionOptions) = {
        Props(new SolrCloudConnection(lbConnection, zkHost, config))
    }

    @SerialVersionUID(1L)
    case class OperateOnCollection(op: Solr.SolrOperation, collection: String)

    object fsm {
        sealed trait State
        case object NotConnected extends State
        case object Connecting extends State
        case object Connected extends State

        case class Data(zkStateReader: ZkStateReader = null)
    }

    private case object Connect
}

class SolrCloudConnection(lbServer: ActorRef, zkHost: String, config: Solr.SolrCloudConnectionOptions)
    extends FSM[fsm.State, fsm.Data] with ActorLogging {

    private val zkUtil = new ZkUtil(config)

    private val stasher = context.actorOf(ConnectingStasher.props, "stasher")

    private val actorName = Util actorNamer "request"

    startWith(fsm.NotConnected, fsm.Data())

    override def preStart() = {
        super.preStart()

        if (config.connectAtStart)
            self ! Connect
    }

    onTermination {
        case StopEvent(_, _, data) ⇒ Try(Option(data.zkStateReader) foreach (_.close())) match {
            case Failure(t) ⇒ log.error(t, "Error closing ZkStateReader")
            case _ ⇒
        }
    }

    private def stash(op: Any, replyTo: ActorRef) = {
        val timeout = op match {
            case OperateOnCollection(so, _) ⇒ so.options.requestTimeout
            case so: Solr.SolrOperation ⇒ so.options.requestTimeout
        }

        stasher ! ConnectingStasher.WaitingRequest(replyTo, op, timeout, timeout)
    }

    private def initiateConnection(msgToStash: Option[Any]) = {
        implicit val dispatcher = context.dispatcher

        msgToStash foreach (s ⇒ stash(s, sender()))

        (zkUtil connect zkHost) pipeTo self
        goto(fsm.Connecting)
    }

    when(fsm.NotConnected) {
        case Event(Connect, _) ⇒ initiateConnection(None)
        case Event(op@(_: OperateOnCollection | _: Solr.SolrOperation), _) ⇒ initiateConnection(Some(op))
    }

    when(fsm.Connecting) {
        case Event(reader: ZkStateReader, _) ⇒ goto(fsm.Connected) using fsm.Data(reader)
        case Event(Status.Failure(t), _) ⇒
            log.error(t, "Error creating ZkStateReader, retrying")
            initiateConnection(None)

        case Event(op@(_: OperateOnCollection | _: Solr.SolrOperation), _) ⇒
            stash(op, sender())
            stay()
    }

    onTransition {
        case fsm.Connecting -> fsm.Connected ⇒ stasher ! ConnectingStasher.FlushWaitingRequests
    }

    when(fsm.Connected) {
        case Event(ConnectingStasher.StashedRequest(replyTo, req, timeout, origTimeout), fsm.Data(reader)) ⇒ ???
        case Event(OperateOnCollection(op, collection), fsm.Data(reader)) ⇒ ???
        case Event(op: Solr.SolrOperation, fsm.Data(reader)) ⇒ ???
    }

    initialize()
}
