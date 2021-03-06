package com.katesoft.gserver.transport;

import static com.google.common.util.concurrent.Uninterruptibles.awaitUninterruptibly;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.net.HostAndPort;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.google.protobuf.GeneratedMessage;
import com.google.protobuf.GeneratedMessage.GeneratedExtension;
import com.googlecode.protobuf.format.JsonFormat;
import com.katesoft.gserver.api.TransportClient;
import com.katesoft.gserver.api.UserConnection;
import com.katesoft.gserver.commands.Commands.BaseCommand;
import com.katesoft.gserver.commands.Commands.BaseCommand.Builder;
import com.katesoft.gserver.commands.Commands.MessageHeaders;
import com.katesoft.gserver.core.CommandsQualifierCodec;
import com.katesoft.gserver.domain.Entities.ServerSettings;

public class NettyTcpClient implements Runnable, TransportClient<SocketChannel> {
    private final Logger logger = LoggerFactory.getLogger( getClass() );
    private final AtomicLong seq = new AtomicLong();
    private final ConcurrentMap<String, SettableFuture<BaseCommand>> corr = Maps.newConcurrentMap();
    private final ConnectionType connectionType;
    private final EventLoopGroup eventGroup = new NioEventLoopGroup();
    private final HostAndPort hostAndPort;
    private final CommandsQualifierCodec codec;
    private SocketChannel sch;
    private ChannelHandlerContext channelCtx;
    private UserConnection userConnection;

    public NettyTcpClient(HostAndPort hostAndPort, CommandsQualifierCodec commandsCodec, ConnectionType type) {
        this.hostAndPort = hostAndPort;
        this.codec = commandsCodec;
        this.connectionType = type;
    }
    @Override
    public void run() {
        final CountDownLatch l = new CountDownLatch( 1 );
        final Bootstrap b = new Bootstrap();

        b
                .group( eventGroup )
                .channel( NioSocketChannel.class )
                .option( ChannelOption.TCP_NODELAY, true )
                .option( ChannelOption.SO_KEEPALIVE, true )
                .option( ChannelOption.SO_SNDBUF, ServerSettings.getDefaultInstance().getTcpSendBufferSize() )
                .option( ChannelOption.SO_RCVBUF, ServerSettings.getDefaultInstance().getTcpReceiveBufferSize() )
                .handler( new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) throws URISyntaxException {
                        ChannelPipeline p = ch.pipeline();
                        if ( connectionType == ConnectionType.TCP ) {
                            NettyServer.registerProtobufCodecs( p, codec.extensionRegistry() );
                            p.addLast( new SimpleChannelInboundHandler<BaseCommand>() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    NettyTcpClient.this.channelCtx = ctx;
                                    sch = (SocketChannel) ctx.pipeline().channel();
                                    logger.debug( "NettyTcpClient connected via={}", sch );
                                    l.countDown();
                                }
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, BaseCommand cmd) {
                                    SettableFuture<BaseCommand> f = corr.remove( cmd.getHeaders().getCorrelationID() );
                                    if ( f != null ) {
                                        f.set( cmd );
                                    }
                                }
                            } );
                        }
                        else if ( connectionType == ConnectionType.WEBSOCKETS ) {
                            final HttpHeaders headers = new DefaultHttpHeaders();
                            final WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                                    new URI( String.format( "ws://%s:%s/websockets", hostAndPort.getHostText(), hostAndPort.getPort() ) ),
                                    WebSocketVersion.V13,
                                    null,
                                    false,
                                    headers );

                            p.addLast( "http-codec", new HttpClientCodec() );
                            p.addLast( "aggregator", new HttpObjectAggregator( 8192 ) );
                            p.addLast( "ws-handler", new SimpleChannelInboundHandler<Object>() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    handshaker.handshake( ctx.channel() );
                                    NettyTcpClient.this.channelCtx = ctx;
                                    sch = (SocketChannel) ctx.pipeline().channel();
                                    logger.debug( "NettyTcpClient connected via={}", sch );
                                }
                                @Override
                                protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
                                    if ( !handshaker.isHandshakeComplete() ) {
                                        handshaker.finishHandshake( ctx.channel(), (FullHttpResponse) msg );
                                        l.countDown();
                                        return;
                                    }

                                    WebSocketFrame frame = (WebSocketFrame) msg;
                                    if ( frame instanceof TextWebSocketFrame ) {
                                        TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
                                        Builder bcmdb = BaseCommand.newBuilder();
                                        JsonFormat.merge( textFrame.text(), codec.extensionRegistry(), bcmdb );
                                        BaseCommand cmd = bcmdb.build();
                                        SettableFuture<BaseCommand> f = corr.remove( cmd.getHeaders().getCorrelationID() );
                                        if ( f != null ) {
                                            f.set( cmd );
                                        }
                                    }
                                    else if ( frame instanceof CloseWebSocketFrame ) {
                                        ctx.channel().close();
                                    }
                                }
                            } );
                        }
                    }
                } );

        b.connect( hostAndPort.getHostText(), hostAndPort.getPort() ).syncUninterruptibly();
        awaitUninterruptibly( l );
    }
    @Override
    public SocketChannel get() {
        return sch;
    }
    @Override
    @SuppressWarnings({ "unchecked" })
    public <Type> ListenableFuture<BaseCommand> callAsync(GeneratedExtension<BaseCommand, Type> ext, Type t, String sessionId) {
        BaseCommand.Builder cmd = BaseCommand.newBuilder().setExtension( ext, t );
        codec.encoder().apply( (Pair<Builder, GeneratedMessage>) ImmutablePair.of( cmd, t ) );

        long seqN = seq.incrementAndGet();
        MessageHeaders headers = MessageHeaders
                .newBuilder()
                .setCorrelationID( getClass().getSimpleName() + ":" + seqN )
                .setMessageTimestamp( System.currentTimeMillis() )
                .setSequenceNumber( seqN )
                .build();
        cmd.setHeaders( headers );
        if ( sessionId != null ) {
            cmd.setSessionId( sessionId );
        }

        SettableFuture<BaseCommand> f = SettableFuture.create();
        corr.put( headers.getCorrelationID(), f );

        try {
            BaseCommand bcmd = cmd.build();
            if ( connectionType == ConnectionType.TCP ) {
                channelCtx.write( bcmd );
            }
            else if ( connectionType == ConnectionType.WEBSOCKETS ) {
                String json = JsonFormat.printToString( bcmd );
                logger.debug( "sending {} via websockets", json );
                channelCtx.write( new TextWebSocketFrame( json ) );
            }
            channelCtx.flush();
        }
        catch ( Throwable e ) {
            logger.error( e.getMessage(), e );
            Throwables.propagate( e );

            corr.remove( headers.getCorrelationID() );
        }

        return f;
    }
    @Override
    public void close() {
        if ( eventGroup != null ) {
            eventGroup.shutdownGracefully();
        }
    }
    public void associateUserConnection(UserConnection uc) {
        this.userConnection = uc;
    }
    public UserConnection getUserConnection() {
        return userConnection;
    }
}
