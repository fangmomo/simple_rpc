package demo.netty;

import demo.annotation.RpcService;
import demo.en_decode.Decoder;
import demo.en_decode.Encoder;
import demo.servicecenter.ServiceCenter;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class NettyServer implements ApplicationContextAware, InitializingBean {

    private static final Logger logger = LoggerFactory.getLogger(NettyServer.class);
    private static final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private static final EventLoopGroup workerGroup = new NioEventLoopGroup(4);


    @Value("${rpc.address}")
    private String serverAddress;

    //缺少服务注册
    @Autowired
    ServiceCenter serviceCenter;

    private Map<String, Object> serviceMap = new HashMap<>();

    @Override
    public void afterPropertiesSet() throws Exception {
        serviceCenter.start();
        logger.info("服务中心启动");
        serverRun();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {

        Map<String, Object> beansWithAnnotation = applicationContext.getBeansWithAnnotation(RpcService.class);

        //根据annotion来获取bean
        for(Object servicebean:beansWithAnnotation.values()){
            Class<?> clazz = servicebean.getClass();
            Class<?>[] interfaces = clazz.getInterfaces();

            for(Class<?> inter:interfaces){
                String intername = inter.getName();
                logger.info("加载服务类:{}",intername);
                serviceMap.put(intername,servicebean);
                System.out.println(intername+servicebean.getClass().getName());
            }
        }
        logger.info("已加载全部服务接口:{}", serviceMap);
    }

    private void serverRun() {

        final NettyServerHandler handler = new NettyServerHandler(serviceMap);
        new Thread(()-> {
            try{
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup).
                        channel(NioServerSocketChannel.class).
                        option(ChannelOption.SO_BACKLOG, 1024).
                        childOption(ChannelOption.SO_KEEPALIVE, true).
                        childOption(ChannelOption.TCP_NODELAY, true).
                        childHandler(new ChannelInitializer<SocketChannel>() {
                            //创建NIOSocketChannel成功后，在进行初始化时，将它的ChannelHandler
                            // 设置到ChannelPipeline中，用于处理网络IO事件
                            @Override
                            protected void initChannel(SocketChannel socketChannel) throws Exception {
                                ChannelPipeline pipeline = socketChannel.pipeline();
                                pipeline.addLast(new IdleStateHandler(0, 0, 60));
                                pipeline.addLast(new Encoder());
                                pipeline.addLast(new Decoder());
                                pipeline.addLast(handler);
                            }
                        });
                String[] array = serverAddress.split(":");
                String host = array[0];
                int port = Integer.valueOf(array[1]);
                ChannelFuture cf = bootstrap.bind(host, port).sync();
                logger.info("RPC 服务器启动，监听端口:"+port);
                //serviceCenter.registe(serverAddress);
                serviceCenter.registe(serviceMap);
                cf.channel().closeFuture().sync();
            }catch(Exception e){
                e.printStackTrace();
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            }

        }).start();

    }
}