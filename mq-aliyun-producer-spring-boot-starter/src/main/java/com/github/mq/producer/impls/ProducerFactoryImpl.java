package com.github.mq.producer.impls;

import com.aliyun.openservices.ons.api.*;
import com.aliyun.openservices.ons.api.order.OrderProducer;
import com.aliyun.openservices.shade.com.alibaba.rocketmq.shade.com.alibaba.fastjson.JSONObject;
import com.github.mq.core.scan.constant.MqConstant;
import com.github.mq.producer.ProducerFactory;
import com.github.mq.producer.ProducerSerialize;
import com.github.mq.producer.models.DeliveryOption;
import com.github.mq.producer.models.PendingMsg;
import com.github.mq.producer.models.Pid;
import com.github.mq.producer.models.To;
import com.google.common.base.Strings;
import com.google.common.collect.Maps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;

import java.util.*;

import static java.lang.Thread.sleep;

/**
 * Created by wangziqing on 17/7/19.
 */
public class ProducerFactoryImpl implements ProducerFactory {
    private final static Logger logger = LoggerFactory.getLogger(ProducerFactoryImpl.class);

    public static final String MQ_PRODUCER_SEND_MSG_TIMEOUT = "aliyun.mq.producer.sendTimeOut";
    public static final String MQ_PRODUCER_PACKAGES = "aliyun.mq.producer.packages";
    private static volatile Map<String, Producer> producerMap = Maps.newConcurrentMap();
    private static volatile Map<String, OrderProducer> orderProducerMap = Maps.newConcurrentMap();

    private static volatile Map<String, Boolean> checkStart = Maps.newConcurrentMap();
    private static volatile Map<String, List<PendingMsg>> pendding = Maps.newConcurrentMap();

    private String suffix;
    private String accessKey;
    private String secretKey;
    private String sendTimeOut = "3000";

    private String[] packages;


    private static final ProducerSerialize producerSerialize;

    static {
        Iterator<ProducerSerialize> producerServiceLoader = ServiceLoader.load(ProducerSerialize.class).iterator();
        if (producerServiceLoader.hasNext()) {
            producerSerialize = producerServiceLoader.next();
        } else {
            producerSerialize = object -> JSONObject.toJSONBytes(object);
        }
    }

    @Override
    public void sendAsync(final Enum address, final Object message) {
        sendAsync(address, message, null, null);
    }

    @Override
    public void sendAsync(final Enum address, final Object message, final DeliveryOption deliveryOption) {
        sendAsync(address, message, deliveryOption, null);

    }

    @Override
    public void sendAsync(final Enum address, final Object message, final SendCallback sendCallback) {
        sendAsync(address, message, null, sendCallback);
    }

    @Override
    public void sendAsync(final Enum address, final Object message, final DeliveryOption deliveryOption, final SendCallback sendCallback) {
        MessageBuild messageBuild = buildMessage(address, message, deliveryOption);
        final String pid = messageBuild.getPid();
        Producer producer = getProducer(pid);
        if (null == producer) {
            Boolean isStart = checkStart.get(pid);
            if (null != isStart && !isStart) {
                List<PendingMsg> messages = pendding.get(pid);
                if (null == messages) {
                    messages = new ArrayList<>();
                }
                messages.add(new PendingMsg(address, message, deliveryOption, sendCallback));
                pendding.put(pid,messages);
                return;
            } else {
                throw new RuntimeException(String.format("发送失败, 无效的pid: %s, address: %s", messageBuild.getPid(),address.name()));
            }
        }
        if (!producer.isStarted()) {
            producer.start();
        }
        if (null != sendCallback) {
            producer.sendAsync(messageBuild.getMessage(), sendCallback);
        } else {
            producer.sendOneway(messageBuild.getMessage());
        }
    }

    @Override
    public SendResult orderSend(final Enum address, final Object message, final String shardingKey) {
        return orderSend(address, message, shardingKey, null);
    }

    @Override
    public SendResult orderSend(final Enum address, final Object message, final String shardingKey, final DeliveryOption deliveryOption) {

        MessageBuild messageBuild = buildMessage(address, message, deliveryOption);
        String pid = messageBuild.getPid();
        OrderProducer orderProducer = getOrderProducer(pid);
        if (null == orderProducer) {
            Boolean isStart = checkStart.get(pid);
            if (null != isStart && !isStart) {
                try {
                    sleep(1500);
                    return this.orderSend(address,message,shardingKey,deliveryOption);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } else {
                throw new RuntimeException(String.format("顺序消息发送失败, 无效的pid: %s, address: %s", messageBuild.getPid(),address.name()));
            }
        }
        if (!orderProducer.isStarted()) {
            orderProducer.start();
        }
        return orderProducer.send(messageBuild.getMessage(), shardingKey);
    }


    private MessageBuild buildMessage(final Enum address, Object message, DeliveryOption deliveryOption) {
        Class<?> addressCls = address.getClass();

        Pid pid = addressCls.getAnnotation(Pid.class);
        if (null == pid) {
            throw new RuntimeException(String.format("无效的address %s not found @Pid", addressCls));
        }

        To to = null;
        try {
            to = addressCls.getField(address.name()).getAnnotation(To.class);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        }
        if (null == to) {
            throw new RuntimeException(String.format("无效的address %s.%s not found @To", addressCls, address.name()));
        }

        Message msg = new Message(
                to.topic() + suffix,
                // 可理解为Gmail中的标签，对消息进行再归类，方便Consumer指定过滤条件在ONS服务器过滤
                to.tag().trim(),
                // Message Body
                // 任何二进制形式的数据， ONS不做任何干预，
                // 需要Producer与Consumer协商好一致的序列化和反序列化方式
                producerSerialize.objToBytes(message));

        if (null != deliveryOption) {
            String key = deliveryOption.getKey();
            if (!Strings.isNullOrEmpty(key)) {
                msg.setKey(key);
            }

            Long deliverTime = deliveryOption.getDeliverTime();

            if (null != deliverTime && 0 != deliverTime)
                msg.setStartDeliverTime(deliverTime);
        }
        return new MessageBuild(pid.value(), msg);
    }

    public void init(Environment env) {
        String package_ = env.getProperty(MQ_PRODUCER_PACKAGES);
        if (null == package_) {
            throw new RuntimeException(String.format("mq生产者 启动失败, %s is require", MQ_PRODUCER_PACKAGES));
        }
        packages = package_.split(",");

        accessKey = env.getProperty(MqConstant.ACCESS_KEY);
        if (null == accessKey || accessKey.trim().isEmpty()) {
            throw new RuntimeException(String.format("mq生产者 启动失败, %s is require", MqConstant.ACCESS_KEY));
        }

        secretKey = env.getProperty(MqConstant.SECRET_KEY);
        if (null == secretKey || secretKey.trim().isEmpty()) {
            throw new RuntimeException(String.format("mq生产者 启动失败, %s is require", MqConstant.SECRET_KEY));
        }

        suffix = env.getProperty(MqConstant.MQ_SUFFIX);
        if (null == suffix || suffix.trim().isEmpty()) {
            throw new RuntimeException(String.format("mq生产者 启动失败, %s is require", MqConstant.MQ_SUFFIX));
        }
        String timeout = env.getProperty(MQ_PRODUCER_SEND_MSG_TIMEOUT);
        if (null != timeout && !timeout.isEmpty()) {
            sendTimeOut = timeout;
        }
    }

    @Order
    public void addProducer(String pid) {
        addProducer(pid, false);
    }

    @Order
    public void addOrdereProducer(String pid) {
        addProducer(pid, true);
    }

    @Override
    public Producer getProducer(String pid) {
        return producerMap.get(pid);
    }

    @Override
    public OrderProducer getOrderProducer(String pid) {
        return orderProducerMap.get(pid);
    }

    public String[] packages() {
        return packages;
    }

    private void addProducer(String pid, boolean ordered) {
        if (ordered) {
            if (null != orderProducerMap.get(pid)) {
                logger.warn("{} 已经注册了", pid);
                return;
            }
        } else {
            if (null != producerMap.get(pid)) {
                logger.warn("{} 已经注册了", pid);
                return;
            }
        }
        checkStart.put(pid, false);
        String pidsuffix = pid + suffix;

        logger.info("发现生产者: {}({})", pidsuffix, ordered ? "有序" : "无序");

        Properties properties = new Properties();
        // AccessKey 阿里云身份验证，在阿里云服务器管理控制台创建
        properties.put(PropertyKeyConst.AccessKey, accessKey);
        // SecretKey 阿里云身份验证，在阿里云服务器管理控制台创建
        properties.put(PropertyKeyConst.SecretKey, secretKey);
        //您在控制台创建的Producer ID
        properties.put(PropertyKeyConst.ProducerId, pidsuffix);
        //设置发送超时时间，单位毫秒
        properties.setProperty(PropertyKeyConst.SendMsgTimeoutMillis, sendTimeOut);


        if (ordered) {
            OrderProducer orderProducer = ONSFactory.createOrderProducer(properties);
            orderProducerMap.put(pid, orderProducer);
            checkStart.put(pid, true);
            orderProducer.start();
        } else {
            Producer producer = ONSFactory.createProducer(properties);
            producerMap.put(pid, producer);
            checkStart.put(pid, true);
            producer.start();

            List<PendingMsg> pendingMsgs = pendding.get(pid);
            if (null != pendingMsgs) {
                for (PendingMsg msg : pendingMsgs) {
                    sendAsync(msg.getAnEnum(), msg.getMessage(), msg.getDeliveryOption(), msg.getSendCallback());
                }
            }
            pendding.remove(pid);
        }

        logger.info("生产者启动成功: {}({})  发送超时时间: {}毫秒", pidsuffix, ordered ? "有序" : "无序", sendTimeOut);
    }

    private static class MessageBuild {
        private String pid;
        private Message message;

        public MessageBuild(String pid, Message message) {
            this.pid = pid;
            this.message = message;
        }

        public String getPid() {
            return pid;
        }

        public Message getMessage() {
            return message;
        }
    }
}
