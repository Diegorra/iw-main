package es.ucm.fdi.iw.controller;

import es.ucm.fdi.iw.LocalData;
import es.ucm.fdi.iw.model.*;
import es.ucm.fdi.iw.model.User.Role;
import es.ucm.fdi.iw.model.WeekPlanMeal.DayTime;
import es.ucm.fdi.iw.model.WeekPlanMeal.WeekDay;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import javax.persistence.EntityManager;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import javax.transaction.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 *  User management.
 *
 *  Access to this end-point is authenticated.
 */
@Controller()
@RequestMapping("user")
public class UserController {

	private static final Logger log = LogManager.getLogger(UserController.class);

	@Autowired
	private EntityManager entityManager;

	@Autowired
    private LocalData localData;

	@Autowired
	private SimpMessagingTemplate messagingTemplate;

	@Autowired
	private PasswordEncoder passwordEncoder;

	/*--------------------------------------------------------Manejo del Perfil--------------------------------------------------------------------------------*/

	@GetMapping("/{id}")
	public String profile(@PathVariable long id, Model model, HttpSession session){
		User target = entityManager.find(User.class, id);
        model.addAttribute("user", target);
		return "profile";
	}

	@GetMapping("/{id}/settings")
	public String settings(@PathVariable long id, Model model, HttpSession session){
		User target = entityManager.find(User.class, id);
        model.addAttribute("user", target);
		return "settings";
	}

	@ResponseStatus(
		value=HttpStatus.BAD_REQUEST,
		reason="Contraseña incorrecta") 
	public static class incorrectOldPasswordException extends RuntimeException {}

	@Transactional
	@ResponseBody
	@PostMapping("/{id}/passwordSettings")
	public String settings(@PathVariable long id, @RequestBody JsonNode data){
		
		User requester = entityManager.find(User.class, id);
		String sendPassword = data.get("oldPassword").textValue();
	
		if(passwordEncoder.matches(sendPassword, requester.getPassword())){
			requester.setPassword(passwordEncoder.encode(data.get("newPassword").textValue()));
			entityManager.persist(requester);
		}
		else{
			//Ns como controlarlo mejor
			throw new incorrectOldPasswordException();
		}
		return "{}";
	}

	@ResponseStatus(
		value=HttpStatus.BAD_REQUEST,
		reason="Nombre de usuario existente")
	public static class usernameConflicException extends RuntimeException {}

	@Transactional
	@ResponseBody
	@PostMapping("/{id}/userSettings")
	public String settingsData(@PathVariable long id, @RequestBody JsonNode data){
		User requester = entityManager.find(User.class, id);
		String newUserName = data.get("firstname").textValue();

		Long exists =((Number) entityManager
            .createNamedQuery("User.hasUsername")
            .setParameter("username", newUserName)
            .getSingleResult())
			.longValue();
		
		if(exists == 0 || requester.getUsername().equals(newUserName)){//Si no existe ese nombre o es propio del usuario 
			requester.setUsername(newUserName);
			requester.setEmail(data.get("email").textValue());
			requester.setAddress(data.get("address").textValue());
			entityManager.persist(requester);
		}
		else{
			//Ns como controlarlo mejor
			throw new usernameConflicException();
		}
		return "{}";
	}

	/**
	 * Sing out the usser in the session
	 *
	 * @param model
	 * @param session
	 * @return index
	 */
	@GetMapping("/logout")
	public String logout(Model model, HttpSession session){
		session.invalidate();
		return "redirect:/";
	}

	@Transactional
	@ResponseBody
	@PostMapping("/deleteUser")
	public String deleteUser(Model model, HttpSession session){
		User u = (User)session.getAttribute("u");
		User ud = entityManager.find(User.class, u.getId());
		for(Recipe r: ud.getRecipes()){
			removeRecipeImg(r.getId());
		}
		entityManager.remove(ud);
		session.invalidate();
		entityManager.flush();
		return "{}";
	}
	

	/*--------------------------------------------------------Manejo del Checkout--------------------------------------------------------------------------------*/

	@GetMapping("/checkout")
	public String checkout(Model model, HttpSession session) {
		Orders order = (Orders)session.getAttribute("order");
		model.addAttribute("order", order);
		return "checkout";
	}

	
	@ResponseBody
	@PostMapping("/addToCart")
	public String addToCart(Model model, @RequestBody JsonNode data, HttpSession session){
		session.setAttribute("order", addToOrder(entityManager.find(
			Recipe.class, data.get("receta").asLong()), data, session));
		return "{}";
	}

	private Orders addToOrder(Recipe r, JsonNode data, HttpSession session){
		
		Orders order = (Orders)session.getAttribute("order");
		Boolean existente = false;
		if (order !=  null) {
			for(OrderRecipe recipe :order.getRecipes()){
				if(recipe.getRecipe().getId() ==  data.get("receta").asLong()){
					existente = true;
					recipe.setQuantity(recipe.getQuantity()+1);
					break;
				}
			}
		}
		if(!existente){
			Recipe receta = entityManager.find(Recipe.class, data.get("receta").asLong());
			for (RecipeIngredient ri : receta.getIngredients()) {
				ri.getIngredient().getAllergen();
			}
			OrderRecipe orderRecipe = new OrderRecipe();
			orderRecipe.setId(UserController.nextOrderId(session));
			orderRecipe.setRecipe(receta);
			orderRecipe.setQuantity(1);
			order.addRecipe(orderRecipe);
		}
		order.actPrecio();
		return order;
	}

	@Transactional
	@ResponseBody
	@PostMapping("/removeFromCart")
	public String removeFromCard(Model model, @RequestBody JsonNode data, HttpSession session){

		Orders order = (Orders)session.getAttribute("order");
		order.removeRecipe(data.get("receta").asLong());
		session.setAttribute("order", order);
		return "{}";
	}

	@Transactional
	@ResponseBody
	@PostMapping("/changequantCart")
	public String changequantCart(Model model, @RequestBody JsonNode data, HttpSession session){
		Orders order = (Orders)session.getAttribute("order");
		order.changeQuant(data.get("receta").asLong(), data.get("quantity").asInt());
		session.setAttribute("order", order);

		return "{}";
	}

	private static int nextOrderId(HttpSession session) {
		int next = (Integer)session.getAttribute("orderId");
		session.setAttribute("orderId", next + 1);
		return next;
	}

	@Transactional
	@GetMapping("/payment")
	public String placeOrder(Model model, HttpSession session) throws JsonProcessingException {
		Orders order = (Orders)session.getAttribute("order");



		if(order.getRecipes().isEmpty()){
			return "redirect:/";
		}

		List<OrderRecipe> recipes = new ArrayList<>();
		order.setState(Orders.State.RECEIVED);

		for(OrderRecipe recipe : order.getRecipes()){
			OrderRecipe newrecipe = new OrderRecipe();
			newrecipe.setRecipe(recipe.getRecipe());
			newrecipe.setQuantity(recipe.getQuantity());
			entityManager.persist(newrecipe);
			recipes.add(newrecipe);
		}

		Long userId = ((User)session.getAttribute("u")).getId();
		User requester = entityManager.find(User.class, userId);

		//Subimos el pedido a la bd
		order.setDirection(requester.getAddress());
		order.setRecipes(recipes);
		model.addAttribute("order", order);
		entityManager.persist(order);
		entityManager.flush();

		//Restauramos el order en la session para que pueda realizar nuevos pedidos
		Orders o = new Orders();
		o.setUser(order.getUser());
		session.setAttribute("order", o);
		session.setAttribute("orderId", 1);

		//enviando por ws el order a la página de admin
		ObjectMapper mapper = new ObjectMapper();
		String json = mapper.writeValueAsString(order.toTransfer());

		log.info("User {} order: '{}'", order.getUser().getId(), json);

		messagingTemplate.convertAndSend("/topic/admin", json);

		return "/payment";
	}

	/*--------------------------------------------------------Manejo del WeekPlan--------------------------------------------------------------------------------*/

	@Transactional
	@GetMapping("/weekplan")
	public String weekplan(Model model, HttpSession session){
		User requester = (User)session.getAttribute("u");
		User u = entityManager.find(User.class, requester.getId());
		List<Recipe> recipes = entityManager.createQuery("select r from Recipe r", Recipe.class).getResultList();
		model.addAttribute("recipes", recipes);
		model.addAttribute("weekdays", WeekPlanMeal.WeekDay.values());
		model.addAttribute("daytimes", WeekPlanMeal.DayTime.values());
		model.addAttribute("user", u);
		return "weekplan";
	}

	@Transactional
	@ResponseBody
	@PostMapping("/weekplan/removeMeal")
	public String removeMeal(Model model, @RequestBody JsonNode data, HttpSession session){
		User requester = (User)session.getAttribute("u");
		User u = entityManager.find(User.class, requester.getId());
		u.removeMeal(WeekDay.valueOf(data.get("day").asText()), DayTime.valueOf(data.get("time").asText()), entityManager);
		return "{}";
	}

	@Transactional
	@ResponseBody
	@PostMapping("/weekplan/add")
	public String addMeal(Model model,  @RequestBody JsonNode data, HttpSession session){
		User requester = (User)session.getAttribute("u");
		User u = entityManager.find(User.class, requester.getId());
		u.assignMeal( entityManager.find(Recipe.class, data.get("recipe").asLong()),
						WeekDay.valueOf(data.get("day").asText()),
						DayTime.valueOf(data.get("time").asText()),
						entityManager);
		entityManager.flush();
		return "{}";
	}

	@Transactional
	@ResponseBody
	@PostMapping("/weekplan/addToCart")
	public String addMealToCart(Model model, @RequestBody JsonNode data, HttpSession session){
		return addToCart(model, data, session); // en data pasamos receta: recipeId;
	}

	@Transactional
	@ResponseBody
	@PostMapping("/weekplan/allToCart")
	public String allToCart(Model model, HttpSession session){
		User requester = (User)session.getAttribute("u");
		User u = entityManager.find(User.class, requester.getId());
		List<WeekPlanMeal> meals = u.getMeals();
		for(WeekPlanMeal m: meals){
			Recipe r = m.getRecipe();
			String json = "{\"receta\":" + r.getId() + "}";
			ObjectMapper mapper = new ObjectMapper();
			JsonNode node;
			try {
				node = mapper.readTree(json);
				addToCart(model,node,session); // en data pasamos receta: recipeId;
			} catch (JsonMappingException e) { // excepciones requeridas por readTree()
				e.printStackTrace();
			} catch (JsonProcessingException e) {
				e.printStackTrace();
			}

		}
		return "{}";
	}

	/*--------------------------------------------------------Manejo de Recetas--------------------------------------------------------------------------------*/

	@GetMapping("/addRecipe")
	public String newRecipe(Model model){
		List<Ingredient> ingredients = entityManager.createQuery("select i from Ingredient i", Ingredient.class).getResultList();
		model.addAttribute("ingredients",ingredients);
		return "/Forms/recipeForm";
	}

	@ResponseBody
	@Transactional
	@PostMapping("/addRecipe")
	public String newRecipe(Model model, @RequestBody JsonNode data, HttpSession session){
		Recipe recipeNew = new Recipe();
		User requester = (User)session.getAttribute("u");

		//File f = localData.getFile("user", ""+id+".jpg");


		ArrayList<RecipeIngredient> ingredientes = new ArrayList<RecipeIngredient>();
		ArrayList<String> noOfficialIngredients  = new ArrayList<String>();
		JsonNode it = data.get("ingredientNames");
		JsonNode it2 = data.get("ingredientCant");
		BigDecimal recipePrice = BigDecimal.ZERO;
		for(int i = 0; i < it.size(); i++){


			List<Ingredient> is = entityManager.createNamedQuery("Ingredient.byName", Ingredient.class)
				.setParameter("iname", it.get(i).asText())
				.getResultList();


			if(is.size() == 0) {
				if(it.get(i).asText() != ""){
					String ing = it.get(i).asText() + " x " + it2.get(i).asInt();
					noOfficialIngredients.add(ing);
				}
				
				continue;
			}
			RecipeIngredient ingredienteCompleto = new RecipeIngredient();

			ingredienteCompleto.setIngredient(is.get(0));
			ingredienteCompleto.setQuantity(it2.get(i).asInt());
			recipePrice = recipePrice.add(is.get(0).getPrice().multiply(new BigDecimal(it2.get(i).asInt())));
			ingredientes.add(ingredienteCompleto);
		}
		recipeNew.setIngredients(ingredientes);
		recipeNew.setNoOfficialIngredients(noOfficialIngredients);
		recipeNew.setDescription(data.get("description").textValue());
		recipeNew.setAuthor(entityManager.find(User.class, requester.getId()));
		//recipeNew.setAuthor((User)session.getAttribute("u"));
		recipeNew.setName(data.get("name").textValue());

		recipeNew.setPrice(recipePrice);
		recipeNew.setDateRegistered(LocalDateTime.now());
		entityManager.persist(recipeNew);
		entityManager.flush();
		session.setAttribute("tmpRecipeId", recipeNew.getId());
		return "{}";
	}

	@PostMapping("/addRecipeImage")
	@ResponseBody
    public String addRecipeImage(@RequestParam("photo") MultipartFile photo,
        HttpServletResponse response, HttpSession session, Model model) throws IOException {



		long id = (long)session.getAttribute("tmpRecipeId");

		File f = localData.getFile("recipes", "" + id + ".jpg");
		log.info("Updating photo for recipe {} at {}", id, f.getAbsolutePath());

		//String id = "pruebaReceta";
		//log.info("Updating photo for user {}", id);
		//File f = localData.getFile("user", ""+id+".jpg");

		if (photo.isEmpty()) {
			log.info("----------------------------------------------------------------------------------------------------------------------------------");
		} else {
			try (BufferedOutputStream stream =
					new BufferedOutputStream(new FileOutputStream(f))) {
				byte[] bytes = photo.getBytes();
				stream.write(bytes);

			} catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

			}
		}
		//Recipe newRecipe = entityManager.find(Recipe.class, id);
		return "{}";
    }

	@GetMapping("{id}/getRecipeImg")
    public StreamingResponseBody getRecipeImg(@PathVariable long id) throws IOException {
        File f = localData.getFile("recipes", ""+id+".jpg");
		if(!f.exists()){
			f = localData.getFile("recipes", "default-pic.jpg");
		}
        return getImageInternaltmp(f);
    }

	private void removeRecipeImg(long id){
		File f = localData.getFile("recipes", ""+id+".jpg");
		if(f.exists()){
			f.delete();
		}
	}


	/**
	 * Remove the recipe con id "id" if the requester is the author or is the admin
	 *
	 * @param id
	 * @param model
	 * @param session
	 * @return index
	 */
	@Transactional
	@PostMapping("/removeRecipe/{id}")
	public String removeRecipe(@PathVariable long id, Model model, HttpSession session){
		//Comprobamos que el usuario que quiere borrar es admin o el propio autor
		Recipe recipe = entityManager.find(Recipe.class, id);
		User requester = (User)session.getAttribute("u");
		if ((requester.getId() == recipe.getAuthor().getId()) || requester.hasRole(Role.ADMIN)) {
			entityManager.remove(recipe);//borramos la receta
			this.removeRecipeImg(id);
		}else{
			throw new NoEsTuPerfilException();
		}

		return "redirect:/";
	}

	@GetMapping("/editRecipe/{id}")
	public String editRecipe(@PathVariable long id, Model model, HttpSession session){
		Recipe recipe = entityManager.find(Recipe.class, id);
		User requester = (User)session.getAttribute("u");
		if ((requester.getId() == recipe.getAuthor().getId())) {
			List<Ingredient> ingredients = entityManager.createQuery("select i from Ingredient i", Ingredient.class).getResultList();
			model.addAttribute("ingredients",ingredients);
			model.addAttribute("recipe", recipe);
			
		}else{
			throw new NoEsTuPerfilException();
		}
		return "/Forms/editRecipeForm";
	}

	@PostMapping("/editRecipeImage/{id}")
	@ResponseBody
    public String editRecipeImage(@PathVariable long id, @RequestParam("photo") MultipartFile photo,
        HttpServletResponse response, HttpSession session, Model model) throws IOException {

		File f = localData.getFile("recipes", "" + id + ".jpg");
		log.info("Updating photo for recipe {} at {}", id, f.getAbsolutePath());

		if (photo.isEmpty()) {
			log.info("----------------------------------------------------------------------------------------------------------------------------------");
		} else {
			try (BufferedOutputStream stream =
					new BufferedOutputStream(new FileOutputStream(f))) {
				byte[] bytes = photo.getBytes();
				stream.write(bytes);

			} catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);

			}
		}
		return "{}";
    }

	@ResponseBody
	@Transactional
	@PostMapping("/editRecipe/{id}")
	public String editRecipeInfo(@PathVariable long id, Model model, @RequestBody JsonNode data, HttpSession session){
		Recipe recipe = entityManager.find(Recipe.class, id);;
		User requester = (User)session.getAttribute("u");
		//Se puede refactorizar utlizando funcion
		ArrayList<RecipeIngredient> ingredientes = new ArrayList<RecipeIngredient>();
		ArrayList<String> noOfficialIngredients  = new ArrayList<String>();
		JsonNode it = data.get("ingredientNames");
		JsonNode it2 = data.get("ingredientCant");
		BigDecimal recipePrice = BigDecimal.ZERO;
		for(int i = 0; i < it.size(); i++){
			List<Ingredient> is = entityManager.createNamedQuery("Ingredient.byName", Ingredient.class)
				.setParameter("iname", it.get(i).asText())
				.getResultList();


			if(is.size() == 0) {
				if(it.get(i).asText() != ""){
					String ing = it.get(i).asText() + " x " + it2.get(i).asInt();
					noOfficialIngredients.add(ing);
				}
				
				continue;
			};
			RecipeIngredient ingredienteCompleto = new RecipeIngredient();

			ingredienteCompleto.setIngredient(is.get(0));
			ingredienteCompleto.setQuantity(it2.get(i).asInt());
			recipePrice = recipePrice.add(is.get(0).getPrice().multiply(new BigDecimal(it2.get(i).asInt())));
			ingredientes.add(ingredienteCompleto);
		}

		recipe.setIngredients(ingredientes);
		recipe.setNoOfficialIngredients(noOfficialIngredients);
		recipe.setDescription(data.get("description").textValue());
		//recipe.setAuthor(entityManager.find(User.class, requester.getId()));
		//recipeNew.setAuthor((User)session.getAttribute("u"));
		recipe.setName(data.get("name").textValue());
		recipe.setPrice(recipePrice);
		recipe.setDateRegistered(LocalDateTime.now());
		entityManager.persist(recipe);
		entityManager.flush();
		//session.setAttribute("tmpRecipeId", recipe.getId());
		return "{}";
	}


	
	/*--------------------------------------------------------Manejo del Comentarios--------------------------------------------------------------------------------*/


	@Transactional
	@PostMapping("newComment/{id}")
	public String newComment(@PathVariable long id, @RequestParam String rating, @RequestParam String text, HttpSession session){
		Recipe recipe = entityManager.find(Recipe.class, id);
		Integer assessment = Integer.parseInt(rating)*20;

		recipe.setAverageRating(assessment);
		entityManager.persist(recipe);
		Comment comment = new Comment((User) session.getAttribute("u"),  recipe, text, assessment);
		entityManager.persist(comment);
		return "redirect:/recipe/" + id;
	}
	/*-------------------------------------------------------------------------------------------------------------------------------------------------------------*/

	/**
     * Exception to use when denying access to unauthorized users.
     *
     * In general, admins are always authorized, but users cannot modify
     * each other's profiles.
     */
	@ResponseStatus(
		value=HttpStatus.FORBIDDEN,
		reason="No eres administrador, y éste no es tu perfil")  // 403
	public static class NoEsTuPerfilException extends RuntimeException {}

	/**
	 * Encodes a password, so that it can be saved for future checking. Notice
	 * that encoding the same password multiple times will yield different
	 * encodings, since encodings contain a randomly-generated salt.
	 * @param rawPassword to encode
	 * @return the encoded password (typically a 60-character string)
	 * for example, a possible encoding of "test" is
	 * {bcrypt}$2y$12$XCKz0zjXAP6hsFyVc8MucOzx6ER6IsC1qo5zQbclxhddR1t6SfrHm
	 */
	public String encodePassword(String rawPassword) {
		return passwordEncoder.encode(rawPassword);
	}

    /**
     * Generates random tokens. From https://stackoverflow.com/a/44227131/15472
     * @param byteLength
     * @return
     */
    public static String generateRandomBase64Token(int byteLength) {
        SecureRandom secureRandom = new SecureRandom();
        byte[] token = new byte[byteLength];
        secureRandom.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token); //base64 encoding
    }

    /**
     * Alter or create a user
     */
	@PostMapping("/{id}")
	@Transactional
	public String postUser(
			HttpServletResponse response,
			@PathVariable long id,
			@ModelAttribute User edited,
			@RequestParam(required=false) String pass2,
			Model model, HttpSession session) throws IOException {

        User requester = (User)session.getAttribute("u");
        User target = null;
        if (id == -1 && requester.hasRole(Role.ADMIN)) {
            // create new user with random password
            target = new User();
            target.setPassword(encodePassword(generateRandomBase64Token(12)));
            target.setEnabled(true);
            entityManager.persist(target);
            entityManager.flush(); // forces DB to add user & assign valid id
            id = target.getId();   // retrieve assigned id from DB
        }

        // retrieve requested user
        target = entityManager.find(User.class, id);
        model.addAttribute("user", target);

		if (requester.getId() != target.getId() &&
				! requester.hasRole(Role.ADMIN)) {
			throw new NoEsTuPerfilException();
		}

		if (edited.getPassword() != null) {
            if ( ! edited.getPassword().equals(pass2)) {
                // FIXME: complain
            } else {
                // save encoded version of password
                target.setPassword(encodePassword(edited.getPassword()));
            }
		}
		target.setUsername(edited.getUsername());


		// update user session so that changes are persisted in the session, too
        if (requester.getId() == target.getId()) {
            session.setAttribute("u", target);
        }

		return "profile";
	}

    /**
     * Returns the default profile pic
     *
     * @return
     */
    private static InputStream defaultPic(String defaultPicLocation) {
		log.info("----" + defaultPicLocation);
	    return new BufferedInputStream(Objects.requireNonNull(
            UserController.class.getClassLoader().getResourceAsStream(
				"/data/recipes/default-pic.jpg")));
    }


	/*private StreamingResponseBody getImageInternal(File f, String defaultPicLocation) throws IOException {
		
		InputStream in = new BufferedInputStream(f.exists() ?
			new FileInputStream(f) : UserController.defaultPic(defaultPicLocation));
		return os -> FileCopyUtils.copy(in, os);
	}*/

	private StreamingResponseBody getImageInternaltmp(File f) throws IOException {
		
		InputStream in = new BufferedInputStream(new FileInputStream(f));
		return os -> FileCopyUtils.copy(in, os);
	}
	/*
     * Downloads a profile pic for a user id
     * 
     * @param id
     * @return
     * @throws IOException
     */
    @GetMapping("{id}/pic")
    public StreamingResponseBody getPic(@PathVariable long id) throws IOException {
        File f = localData.getFile("users", ""+id+".jpg");
		if(!f.exists()){
			f = localData.getFile("users", "default-pic.jpg");
		}
		//String defaultPicLocation = "users/default-pic.jpg";
        return getImageInternaltmp(f);
    }

    /**
     * Uploads a profile pic for a user id
     * 
     * @param id
     * @return
     * @throws IOException
     */
    @PostMapping("{id}/pic")
	@ResponseBody
    public String setPic(@RequestParam("photo") MultipartFile photo, @PathVariable long id, 
        HttpServletResponse response, HttpSession session, Model model) throws IOException {

        User target = entityManager.find(User.class, id);
        model.addAttribute("user", target);
		
		// check permissions
		User requester = (User)session.getAttribute("u");
		if (requester.getId() != target.getId() &&
				! requester.hasRole(Role.ADMIN)) {
            throw new NoEsTuPerfilException();
		}
		
		log.info("Updating photo for user {}", id);
		File f = localData.getFile("users", ""+ id + ".jpg");
		if (photo.isEmpty()) {
			log.info("failed to upload photo: emtpy file?");
		} else {
			try (BufferedOutputStream stream =
					new BufferedOutputStream(new FileOutputStream(f))) {
				byte[] bytes = photo.getBytes();
				stream.write(bytes);
                log.info("Uploaded photo for {} into {}!", id, f.getAbsolutePath());
			} catch (Exception e) {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
				log.warn("Error uploading " + id + " ", e);
			}
		}
		return "{\"status\":\"photo uploaded correctly\"}";
    }


	

}