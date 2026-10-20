package local.runningdashboard;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
class FrontendController {
    @GetMapping({"/", "/fixture-summary", "/import", "/training-plan", "/activities/{id}"})
    String index() {
        return "forward:/index.html";
    }
}
