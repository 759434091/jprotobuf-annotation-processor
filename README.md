# jprotobuf-annotation-processor

代替jProtobuf-precompile-plugin插件

预编译Protobuf相关类，适应SpringBoot fatjar

-----

代替插件
````
<!--<groupId>com.baidu</groupId>-->
<!--<artifactId>jprotobuf-precompile-plugin</artifactId>-->
````

详情见    
[Jprotobuf-rpc-socket](https://github.com/baidu/Jprotobuf-rpc-socket)

## 使用方法
改注解处理器依赖jProtobuf以及Lombok
````
        <dependency>
            <groupId>com.baidu</groupId>
            <artifactId>jprotobuf</artifactId>
            <version>1.11.6</version>
        </dependency>

        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <version>1.18.8</version>
            <optional>true</optional>
        </dependency>
        
        <dependency>
            <groupId>com.github.hept59434091.jprotobuf</groupId>
            <artifactId>jprotobuf-annotation-processor</artifactId>
            <version>1.0.0</version>
            <optional>true</optional>
        </dependency>
````

