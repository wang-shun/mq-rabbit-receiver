package com.ymatou.mq.rabbit.receiver.service;

import com.alibaba.dubbo.config.annotation.Reference;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializeConfig;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.ymatou.mq.infrastructure.model.CallbackConfig;
import com.ymatou.mq.infrastructure.model.Message;
import com.ymatou.mq.infrastructure.service.MessageConfigService;
import com.ymatou.mq.rabbit.RabbitChannelFactory;
import com.ymatou.mq.rabbit.config.RabbitConfig;
import com.ymatou.mq.rabbit.dispatcher.facade.MessageDispatchFacade;
import com.ymatou.mq.rabbit.receiver.config.ReceiverConfig;
import com.ymatou.mq.rabbit.support.ChannelWrapper;
import com.ymatou.mq.rabbit.support.RabbitConstants;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.SerializationUtils;

import java.io.IOException;
import java.util.*;

/**
 * rabbit生产者
 * Created by zhangzhihua on 2017/3/23.
 */
@Component("rabbitProducer")
public class RabbitProducer {

    private static final Logger logger = LoggerFactory.getLogger(RabbitProducer.class);

    /**
     * rabbit配置信息
     */
    @Autowired
    private RabbitConfig rabbitConfig;

    @Autowired
    private ReceiverConfig receiverConfig;

    @Autowired
    private MessageConfigService messageConfigService;

    @Reference
    private MessageDispatchFacade messageDispatchFacade;

    /**
     * 发布消息
     * @param exchange
     * @param message
     * @throws IOException
     */
    public void publish(String exchange, Message message) throws IOException {
        String msgId = message.getId();
        String bizId = message.getBizId();

        logger.debug("RabbitProducer.publish,current thread name:{},thread id:{}",Thread.currentThread().getName(),Thread.currentThread().getId());
        //获取channel
        ChannelWrapper channelWrapper = RabbitChannelFactory.getChannelWrapper(receiverConfig.getCurrentCluster(),rabbitConfig);
        Channel channel = channelWrapper.getChannel();
        //若是第一次创建channel，则初始化ack相关
        if(channelWrapper.getUnconfirmedMap() == null){
            //设置channel对应的unconfirmedSet、acklistener信息
            SortedMap<Long, Object> unconfirmedSet = Collections.synchronizedSortedMap(new TreeMap<Long, Object>());
            channelWrapper.setUnconfirmedMap(unconfirmedSet);

            RabbitAckListener rabbitAckListener = new RabbitAckListener(channelWrapper,messageDispatchFacade);
            channel.addConfirmListener(rabbitAckListener);
            channel.confirmSelect();
        }

        //设置ack关联数据
        channelWrapper.getUnconfirmedMap().put(channel.getNextPublishSeqNo(),message);

        AMQP.BasicProperties basicProps = new AMQP.BasicProperties.Builder()
                .messageId(msgId).correlationId(bizId)
                .deliveryMode(RabbitConstants.DELIVERY_PERSISTENT)
                .build();

        String routeKey = getRouteKey(message.getAppId(),message.getQueueCode());
        if(StringUtils.isNoneBlank(routeKey)){
            channel.basicPublish(exchange, routeKey, basicProps, toBytesByJava(message));
        }
    }

    /**
     * 通过java序列化
     * @param message
     * @return
     */
    byte[] toBytesByJava(Message message){
        //FIXME:中文等非Ascii码传输，有编码问题吗
        //TODO 设置编码
        return SerializationUtils.serialize(message);
    }

    /**
     * 通过fastjson序列化
     * @param message
     * @return
     */
    byte[] toBytesByFastJson(Message message){
        SerializeConfig serializeConfig = new SerializeConfig();
        SerializerFeature[] serializerFeatures = {SerializerFeature.WriteClassName};
        return  JSON.toJSONBytes(message,serializeConfig,serializerFeatures);
    }

    /**
     * 获取routeKey
     * @param appId
     * @param queueCode
     * @return
     */
    String getRouteKey(String appId,String queueCode){
        StringBuffer buf = new StringBuffer();
        List<CallbackConfig> callbackConfigList = messageConfigService.getCallbackConfigList(appId,queueCode);
        int i = 0;
        for(CallbackConfig callbackConfig:callbackConfigList){
            if(callbackConfig.isDispatchEnable()){
                if(i == 0){
                    buf.append(getCallbackNo(callbackConfig.getCallbackKey()));
                }else{
                    buf.append(String.format(".%s",getCallbackNo(callbackConfig.getCallbackKey())));
                }
                i++;
            }
        }
        return buf.toString().trim();
    }

    /**
     * 获取callbackKey序号
     * @param callbackKey
     * @return
     */
    String getCallbackNo(String callbackKey){
        return callbackKey.substring(callbackKey.lastIndexOf("_")+1,callbackKey.length());
    }

}
