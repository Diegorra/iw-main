package es.ucm.fdi.iw.controller;

import es.ucm.fdi.iw.model.User;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

import es.ucm.fdi.iw.model.Recipe;
import org.springframework.web.bind.annotation.PostMapping;

import javax.persistence.EntityManager;
import javax.servlet.http.HttpSession;
import javax.transaction.Transactional;

import org.springframework.web.bind.annotation.RequestParam;
import javax.persistence.*;

/**
 *  Non-authenticated requests only.
 */
@Controller
public class RootController {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

	private static final Logger log = LogManager.getLogger(RootController.class);

	@GetMapping("/login")
    public String login(Model model) {
        return "/Forms/login";
    }

    @GetMapping("/register")
    public String register(Model model){
	    User newUser = new User();
	    model.addAttribute("newUser", newUser);
	    return "/Forms/register"; }

    @PostMapping("/register")
    @Transactional
    public String postRegister(@ModelAttribute("newUser") User newUser){
	    newUser.setRoles("USER");
	    newUser.setPassword(passwordEncoder.encode(newUser.getPassword()));
	    newUser.setSrc("https://i.pinimg.com/736x/fb/6f/99/fb6f99f760733ce2c47ee8f57668050b.jpg");
	    newUser.setEnabled(true);
	    entityManager.persist(newUser);
	    return "/Forms/login";
    }

    @GetMapping("/contact")
    public String contact(Model model){return "contact";}

    @GetMapping("/search")
    public String  search(Model model, @RequestParam String recipeName){

        List<Recipe> recipes = entityManager
            .createNamedQuery("findRecipeWithName")
            .setParameter("name", recipeName)
            .getResultList();
        
        model.addAttribute("recipes", recipes);
        return "search";
    }

    @GetMapping("/")
    @Transactional
    public String index(Model model) {
        List<Recipe> recipes = entityManager.createQuery("select r from Recipe r", Recipe.class).getResultList();
        model.addAttribute("recipes", recipes);
        return "index";
    }

    @GetMapping(value= "/recipe/{id}")
    public String recipeInfo(@PathVariable Long id, Model model){
	    Recipe recipe1 = entityManager.find(Recipe.class, id);
	    model.addAttribute("recipe", recipe1);
	    return "/fragments/recipe";
    }

}
