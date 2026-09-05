package com.system.recovery.controller;
import com.system.recovery.service.DemoScenarioService;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/demo")
public class DemoScenarioController { private final DemoScenarioService service; public DemoScenarioController(DemoScenarioService service){this.service=service;} @PostMapping("/scenario-a") public Object a(){return service.scenarioA();} @PostMapping("/scenario-b") public Object b(){return service.scenarioB();} }
