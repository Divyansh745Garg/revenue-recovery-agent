package com.system.recovery.controller; import com.system.recovery.service.BatchRunner; import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/batch") public class BatchController {private final BatchRunner r;public BatchController(BatchRunner r){this.r=r;}@PostMapping("/run") public Object run(@RequestParam(defaultValue="150") int count){return r.run(count);}}
