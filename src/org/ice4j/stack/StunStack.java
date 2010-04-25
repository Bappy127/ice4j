/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * The entry point to the Stun4J stack. The class is used to start, stop and
 * configure the stack.
 *
 * @author Emil Ivov
 */
public class StunStack
    implements MessageEventHandler
{
    /**
     * We shouldn't need more than one stack in the same application.
     */
    private static StunStack stackInstance = null;

    /**
     * Our network gateway.
     */
    private NetAccessManager netAccessManager = null;

    /**
     * The number of threads to split our flow in.
     */
    public static final int DEFAULT_THREAD_POOL_SIZE = 3;

    /**
     * Our class logger.
     */
    private static final Logger logger =
        Logger.getLogger(StunStack.class.getName());

    /**
     * Stores active client transactions mapped against TransactionID-s.
     */
    private final Hashtable<TransactionID, StunClientTransaction>
        clientTransactions
            = new Hashtable<TransactionID, StunClientTransaction>();

    /**
     * Currently open server transactions. The vector contains transaction ids
     * for transactions corresponding to all non-answered received requests.
     */
    private final Hashtable<TransactionID, StunServerTransaction>
        serverTransactions
            = new Hashtable<TransactionID, StunServerTransaction>();

    /**
     * A dispatcher for incoming requests event;
     */
    private final EventDispatcher eventDispatcher = new EventDispatcher();

    /**
     * Returns a reference to the singleton StunStack instance. If the stack
     * had not yet been initialized, a new instance will be created.
     *
     * @return a reference to the StunStack.
     */
    public static synchronized StunStack getInstance()
    {
        if (stackInstance == null)
            stackInstance = new StunStack();

        return stackInstance;
    }

    /**
     * Sets the number of Message processors running in the same time.
     *
     * @param threadPoolSize the number of message process threads to run.
     * @throws IllegalArgumentException if threadPoolSize is not a valid size.
     */
    public void setThreadPoolSize(int threadPoolSize)
        throws IllegalArgumentException
    {
        netAccessManager.setThreadPoolSize(threadPoolSize);
    }

    /**
     * Creates and starts the specified Network Access Point based on the
     * specified socket and returns a relevant descriptor.
     *
     * @param sock The socket that the new access point should represent.
     */
    public void addSocket(DatagramSocket sock)
    {
        netAccessManager.addSocket(sock);
    }

    /**
     * Stops and deletes the connector listening on the specified local address.
     *
     * @param localAddr the access  point to remove
     */
    public void removeSocket(TransportAddress localAddr)
    {
        //first cancel all transactions using this address.
        cancelTransactionsForAddress(localAddr);

        netAccessManager.removeSocket(localAddr);
    }

    /**
     * Stops all transactions for the specified <tt>localAddr</tt> so that they
     * won't send messages through any longer and so that we could remove the
     * associated socket.
     *
     * @param localAddr the <tt>TransportAddress</tt> that we'd like to remove
     * transactions for.
     */
    private void cancelTransactionsForAddress(TransportAddress localAddr)
    {
        synchronized(clientTransactions)
        {
            Iterator<Map.Entry<TransactionID, StunClientTransaction>>
                clientTransactionsIter = clientTransactions.entrySet()
                    .iterator();

            while (clientTransactionsIter.hasNext())
            {
                Map.Entry<TransactionID, StunClientTransaction> entry
                    = clientTransactionsIter.next();

                StunClientTransaction tran = entry.getValue();
                if (tran.getLocalAddress().equals(localAddr))
                    clientTransactionsIter.remove();

                tran.cancel();
            }
        }

        synchronized(serverTransactions)
        {
            Iterator<Map.Entry<TransactionID, StunServerTransaction>>
                serverTransactionsIter = serverTransactions.entrySet()
                    .iterator();

            while (serverTransactionsIter.hasNext())
            {
                Map.Entry<TransactionID, StunServerTransaction> entry
                    = serverTransactionsIter.next();

                StunServerTransaction tran = entry.getValue();

                TransportAddress listenAddr = tran.getLocalListeningAddress();
                TransportAddress sendingAddr = tran.getSendingAddress();

                if ( listenAddr.equals(localAddr)
                     || (sendingAddr != null && sendingAddr.equals(localAddr)) )
                {
                    serverTransactionsIter.remove();
                }

                tran.expire();
            }
        }
    }

    /**
     * Private constructor as we want a singleton pattern.
     */
    private StunStack()
    {
        netAccessManager = new NetAccessManager(this);
    }

    /**
     * Returns the currently active instance of NetAccessManager.
     * @return the currently active instance of NetAccessManager.
     */
    NetAccessManager getNetAccessManager()
    {
        return netAccessManager;
    }

    /**
     * Sends the specified request through the specified access point, and
     * registers the specified ResponseCollector for later notification.
     * @param  request     the request to send
     * @param  sendTo      the destination address of the request.
     * @param  sendThrough the local address to use when sending the request
     * @param  collector   the instance to notify when a response arrives or the
     *                     the transaction timeouts
     *
     * @return the <tt>TransactionID</tt> of the <tt>StunClientTransaction</tt>
     * that we used in order to send the request.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     */
    public TransactionID sendRequest(  Request           request,
                                       TransportAddress  sendTo,
                                       TransportAddress  sendThrough,
                                       ResponseCollector collector )
        throws IOException, IllegalArgumentException
    {
        StunClientTransaction clientTransaction
            = new StunClientTransaction(this, request, sendTo, sendThrough,
                                        collector);

        clientTransactions.put(clientTransaction.getTransactionID(),
                               clientTransaction);

        clientTransaction.sendRequest();

        return clientTransaction.getTransactionID();
    }

    /**
     * Sends the specified request through the specified access point, and
     * registers the specified ResponseCollector for later notification.
     * @param  request     the request to send
     * @param  sendTo      the destination address of the request.
     * @param  sendThrough the socket that we should send the request through.
     * @param  collector   the instance to notify when a response arrives or the
     *                     the transaction timeouts
     *
     * @return the <tt>TransactionID</tt> of the <tt>StunClientTransaction</tt>
     * that we used in order to send the request.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     */
    public TransactionID sendRequest( Request           request,
                                      TransportAddress  sendTo,
                                      DatagramSocket    sendThrough,
                                      ResponseCollector collector )
        throws IOException, IllegalArgumentException
    {
        TransportAddress sendThroughAddr = new TransportAddress(
            sendThrough.getLocalAddress(), sendThrough.getLocalPort(),
                Transport.UDP);

        return sendRequest(request, sendTo, sendThroughAddr, collector);
    }

    /**
     * Sends the specified response message through the specified access point.
     *
     * @param transactionID the id of the transaction to use when sending the
     * response. Actually we are getting kind of redundant here as we already
     * have the id in the response object, but I am bringing out as an extra
     * parameter as the user might otherwise forget to explicitly set it.
     * @param response      the message to send.
     * @param sendThrough   the local address to use when sending the message.
     * @param sendTo        the destination of the message.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws StunException if message encoding fails
     */
    public void sendResponse(byte[]           transactionID,
                             Response         response,
                             TransportAddress sendThrough,
                             TransportAddress sendTo)
        throws StunException,
               IOException,
               IllegalArgumentException
    {
        TransactionID tid = TransactionID.createTransactionID(transactionID);
        StunServerTransaction sTran =
            serverTransactions.get(tid);

        if(sTran == null)
        {
            throw new StunException(StunException.TRANSACTION_DOES_NOT_EXIST,
                                "The transaction specified in the response "
                                + "(tid="+ tid.toString() +") "
                                + "object does not exist.");
        }
        else if( sTran.isReransmitting())
        {
            throw new StunException(StunException.TRANSACTION_DOES_NOT_EXIST,
                                    "The transaction specified in the response "
                                    + "(tid="+ tid.toString() +") "
                                    + "has already seen a previous response. "
                                    + "Response was:\n"
                                    + sTran.getResponse());
        }
        else
        {
            sTran.sendResponse(response, sendThrough, sendTo);
        }
    }

    /**
     * Sets the listener that should be notified when a new Request is received.
     * @param requestListener the listener interested in incoming requests.
     */
    public  void addRequestListener(RequestListener requestListener)
    {
        this.eventDispatcher.addRequestListener( requestListener );
    }

    /**
     * Removes the specified listener from the local listener list. (If any
     * instances of this listener have been registered for a particular
     * access point, they will not be removed).
     * @param listener the RequestListener listener to unregister
     */
    public void removeRequestListener(RequestListener listener)
    {
        this.eventDispatcher.removeRequestListener(listener);
    }

    /**
     * Add a RequestListener for requests coming from a specific NetAccessPoint.
     * The listener will be invoked only when a request event is received on
     * that specific property.
     *
     * @param localAddress The local <tt>TransportAddress</tt> that we would
     * like to listen on.
     * @param listener The ConfigurationChangeListener to be added
     */
    public void addRequestListener( TransportAddress localAddress,
                                    RequestListener  listener)
    {
            eventDispatcher.addRequestListener(localAddress, listener);
    }

    /**
     * Removes a client transaction from this providers client transactions list.
     * Method is used by StunClientTransaction-s themselves when a timeout occurs.
     * @param tran the transaction to remove.
     */
    synchronized void removeClientTransaction(StunClientTransaction tran)
    {
        clientTransactions.remove(tran.getTransactionID());
    }

    /**
     * Removes a server transaction from this provider's server transactions
     * list.
     * Method is used by StunServerTransaction-s themselves when they expire.
     * @param tran the transaction to remove.
     */
    synchronized void removeServerTransaction(StunServerTransaction tran)
    {
        serverTransactions.remove(tran.getTransactionID());
    }

    /**
     * Called to notify this provider for an incoming message.
     *
     * @param event the event object that contains the new message.
     */
    public void handleMessageEvent(StunMessageEvent event)
    {
        Message msg = event.getMessage();

        if(logger.isLoggable(Level.FINEST))
            logger.finest("Received a message on NetAP"
                        + event.getLocalAddress()
                        + " of type:"
                        + (int)msg.getMessageType());

        //request
        if(msg instanceof Request)
        {
            logger.finest("parsing request");
            TransactionID serverTid = TransactionID.
                                    createTransactionID(msg.getTransactionID());

            StunServerTransaction sTran  = serverTransactions.get(serverTid);
            if( sTran != null)
            {
                //requests from this transaction have already been seen
                //retransmit the response if there was any
                logger.finest("found an existing transaction");

                try
                {
                    sTran.retransmitResponse();
                    logger.finest("Response retransmitted");
                }
                catch (Exception ex)
                {
                    //we couldn't really do anything here .. apart from logging
                    logger.log(Level.WARNING,
                               "Failed to retransmit a stun response", ex);
                }

                if(!Boolean.getBoolean(
                        StackProperties.PROPAGATE_RECEIVED_RETRANSMISSIONS))
                {
                    return;
                }
            }
            else
            {
                logger.finest("exising transaction not found");
                sTran = new StunServerTransaction(this, serverTid,
                             event.getLocalAddress(), event.getRemoteAddress());

                serverTransactions.put(serverTid, sTran);
                sTran.start();
            }

            eventDispatcher.fireMessageEvent(event);
        }
        //response
        else if(msg instanceof Response)
        {
            TransactionID tid
                = TransactionID.createTransactionID(msg.getTransactionID());
            StunClientTransaction tran = clientTransactions.remove(tid);

            if(tran != null)
            {
                tran.handleResponse(event);
            }
            else
            {
                //do nothing - just drop the phantom response.
                logger.fine("Dropped response - "
                                            + "no matching client tran found.");
                logger.fine("response tid was - " + tid);
                logger.fine("all tids in stock were" + clientTransactions);
            }
        }
    }

    /**
     * Cancels all running transactions and prepares for garbage collection
     */
    public void shutDown()
    {
        eventDispatcher.removeAllListeners();

        Enumeration<TransactionID> tids = clientTransactions.keys();
        while (tids.hasMoreElements())
        {
            TransactionID item = tids.nextElement();
            StunClientTransaction tran = clientTransactions.remove(item);
            if(tran != null)
                tran.cancel();
        }

        tids = serverTransactions.keys();
        while (tids.hasMoreElements())
        {
            TransactionID item = tids.nextElement();
            StunServerTransaction tran = serverTransactions.remove(item);
            if(tran != null)
                tran.expire();
        }
    }
}