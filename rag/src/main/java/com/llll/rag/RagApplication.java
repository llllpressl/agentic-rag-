package com.llll.rag;

import com.llll.framwork.handler.TimeFillHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.annotation.MapperScans;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(TimeFillHandler.class)
@MapperScan(basePackages = {
        "com.llll.rag.knowledge.dao.mapper"
})
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }

}
