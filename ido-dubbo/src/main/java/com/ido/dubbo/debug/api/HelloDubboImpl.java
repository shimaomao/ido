package com.ido.dubbo.debug.api;


/**
 * @author xu.qiang
 * @date 18/11/28
 */
public class HelloDubboImpl implements HelloDubbo {
    @Override
    public String sayHello(String name) {

        System.out.println("服务端接收到信息:" + name);

        return "Pong";
    }
}
