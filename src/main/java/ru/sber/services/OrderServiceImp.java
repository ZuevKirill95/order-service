package ru.sber.services;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import ru.sber.entities.Order;
import ru.sber.entities.enums.EStatusOrders;
import ru.sber.models.Coordinates;
import ru.sber.models.LimitDishesOrder;
import ru.sber.models.LimitOrder;
import ru.sber.models.LimitOrderRestaurant;
import ru.sber.repositories.DishesOrderRepository;
import ru.sber.repositories.OrderRepository;

/**
 * Контроллер для взаимодействия {@link Order заказами}
 */
@Service
@Slf4j
public class OrderServiceImp implements OrderService {
    private final OrderRepository orderRepository;
    private final DishesOrderRepository dishesOrderRepository;

    @Autowired
    public OrderServiceImp(OrderRepository orderRepository, DishesOrderRepository dishesOrderRepository) {
        this.orderRepository = orderRepository;
        this.dishesOrderRepository = dishesOrderRepository;
    }

    @Override
    public boolean updateOrderStatus(long id, LimitOrderRestaurant orderRequest) {
        log.info("Обновляет статус заказа с id {} на статус {}", id, orderRequest.getStatus());

        Optional<Order> order = orderRepository.findById(id);

        if (order.isPresent()) {
            switch (orderRequest.getStatus()) {
                case "COOKING" -> {
                    Order currentOrder = order.get();
                    currentOrder.setStartCookingTime(LocalDateTime.now());
                    currentOrder.setBranchOfficeId(orderRequest.getBranchId());
                    currentOrder.setBranchAddress(orderRequest.getBranchAddress());
                    currentOrder.setEmployeeRestaurantId(orderRequest.getEmployeeRestaurantId());
                }
                case "COOKED" -> order.get().setEndCookingTime(LocalDateTime.now());
                case "DELIVERY" -> order.get().setDeliveryTime(LocalDateTime.now());
            }

            order.get().setStatusOrders(EStatusOrders.valueOf(orderRequest.getStatus()));
            orderRepository.save(order.get());

            return true;
        }

        return false;
    }

    @Override
    public boolean updateOrderCourierId(String idCourier, long idOrder) {
        log.info("Обновляет курьера (id={}) заказа с id {}", idCourier, idOrder);
        Optional<Order> order = orderRepository.findById(idOrder);
        if(order.isPresent()) {
            order.get().setCourierId(idCourier);
            orderRepository.save(order.get());
            return true;
        }
        return false;
    }

    @Override
    public List<LimitOrderRestaurant> getListOrder() {
        log.info("Получает список заказов со статусом: в ожидании, в процессе готовки и готов");

        List<EStatusOrders> eStatusOrdersList = Arrays.asList(
                EStatusOrders.REVIEW,
                EStatusOrders.COOKING,
                EStatusOrders.COOKED);

        return orderRepository.findByStatusOrdersIn(eStatusOrdersList)
                .stream()
                .map(getLimitOrderRestoranFunction())
                .toList();
    }

    @Override
    public boolean paymentOfOrderById(long idOrder) {
        log.info("Оплачивается заказ с id {}", idOrder);

        Optional<Order> order = orderRepository.findById(idOrder);

        if (order.isPresent()) {
            order.get().setStatusOrders(EStatusOrders.REVIEW);
            orderRepository.save(order.get());

            return true;
        }

        return false;
    }

    @Override
    public boolean cancellationOfOrderById(Long id, String massage) {
        log.info("Отказывается от заказа с id {}", id);

        Optional<Order> order = orderRepository.findById(id);

        if (order.isPresent()) {
            order.get().setStatusOrders(EStatusOrders.CANCELLED);
            order.get().setRefusalReason(massage);
            orderRepository.save(order.get());

            return true;
        }

        return false;
    }

    @Override
    public boolean cancellationOfOrderByListId(String listId, String massage) {
        log.info("Отказывается от заказов с id {}", listId);

        if (massage.isEmpty()){ return false; }

        List<Long> dishIds = Arrays.stream(listId.split(","))
                .map(Long::parseLong)
                .toList();

        dishIds.forEach(item -> {
            Optional<Order> order = orderRepository.findById(item);

            if (order.isPresent()) {
                order.get().setStatusOrders(EStatusOrders.CANCELLED);
                order.get().setRefusalReason(massage);
                orderRepository.save(order.get());
            }
        });

        return true;
    }

    @Override
    public List<LimitOrder> findAllActiveOrder() {
        log.info("Получает список заказов со статусом: готовится, готов");

        List<EStatusOrders> eStatusOrdersList = Arrays.asList(
                EStatusOrders.COOKING,
                EStatusOrders.COOKED);

        return orderRepository.findByStatusOrdersInAndCourierIdNull(eStatusOrdersList)
                .stream()
                .map(getLimitOrderFunction())
                .toList();
    }

    @Override
    public Page<LimitOrder> findAllActiveOrdersByPage(int page, int pageSize) {
        log.info("Получает список заказов со статусом: готовится, готов, ограниченный страницей");
        Pageable pageable = PageRequest.of(page, pageSize);

        List<EStatusOrders> eStatusOrdersList = Arrays.asList(
                EStatusOrders.COOKING,
                EStatusOrders.COOKED);

        return orderRepository.findByStatusOrdersInAndCourierIdNull(eStatusOrdersList, pageable)
                .map(getLimitOrderFunction());
   }

    @Override
    public Optional<LimitOrder> findOrderById(long id) {
        log.info("Поиск заказа с id {}", id);

        Optional<Order> order = orderRepository.findById(id);

        if (order.isPresent()) {
            List<LimitDishesOrder> dishesOrders = dishesOrderRepository.findAllByOrderId(id)
                    .stream()
                    .map(LimitDishesOrder::new)
                    .toList();

            return Optional.of(new LimitOrder(order.get(), dishesOrders));
        }

        return Optional.empty();
    }

    @Override
    public Optional<LimitOrder> findOrderByIdWithCoordinates(long id) {
        log.info("Поиск заказа с id {}", id);

        Optional<LimitOrder> limitOrder = findOrderById(id);

        if (limitOrder.isPresent()) {
            LimitOrder legitLimitOrder = limitOrder.get();

            try {
                legitLimitOrder.setBranchAddressCoordinates(requestCoordinates(legitLimitOrder.getBranchAddress()));
                legitLimitOrder.setAddressCoordinates(requestCoordinates(legitLimitOrder.getAddress()));
            } catch (IOException e) {
                e.printStackTrace();
            }

            return Optional.of(legitLimitOrder);
        }

        return Optional.empty();
    }

    public Coordinates requestCoordinates(String address) throws IOException {
        String apiKey = "8ec18778-cb70-437f-87fc-7c17e8e0bb71";
        RestTemplate restTemplate = new RestTemplate();
        String url = "https://geocode-maps.yandex.ru/1.x/?apikey=" + apiKey + "&geocode=" + address+ "&format=json";
        ResponseEntity<?> response = restTemplate.exchange(url, HttpMethod.GET, null, Object.class);
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode rootNode = objectMapper.readTree(objectMapper.writeValueAsString(response.getBody()));
        log.info("Определение по адресу: {}", rootNode);
        JsonNode posNode = rootNode.path("response").path("GeoObjectCollection").path("featureMember").get(0).path("GeoObject").path("Point").path("pos");
        String pos = posNode.asText();
        log.info("Ответ: {}.", pos);
        String[] numbers = pos.split(" ");

        return new Coordinates(new BigDecimal(numbers[0]), new BigDecimal(numbers[1]));
    }

    @Override
    public Page<LimitOrder> findOrdersByCourierId(String id, int page, int pageSize) {
        log.info("Получает список всех заказов курьера, ограниченный страницей");
        Pageable pageable = PageRequest.of(page, pageSize);

        return orderRepository.findOrderByCourierId(id, pageable)
                .map(getLimitOrderFunction());
    }

    @Override
    public List<LimitOrder> findOrdersCourierIsDelivering(String id) {
        return orderRepository.findOrderByCourierIdAndStatusOrdersNot(id, EStatusOrders.COMPLETED)
                .stream()
                .map(getLimitOrderFunction())
                .toList();
    }

    @Override
    public List<LimitOrderRestaurant> getListOrderByNotify(String ordersId) {
        List<Long> dishIds = Arrays.stream(ordersId.split(","))
                .map(Long::parseLong)
                .toList();

        return orderRepository.findAllById(dishIds).stream()
                .map(getLimitOrderRestoranFunction())
                .toList();
    }

    /**
     * Преобразует класс Order {@link Order} в {@link LimitOrder}
     *
     * @return урезанный заказ
     */
    private Function<Order, LimitOrder> getLimitOrderFunction() {
        return order -> {
            List<LimitDishesOrder> dishesOrders = dishesOrderRepository.findAllByOrderId(order.getId())
                    .stream()
                    .map(LimitDishesOrder::new)
                    .toList();
            return new LimitOrder(order, dishesOrders);
        };
    }

    /**
     * Преобразует класс Order {@link Order} в {@link LimitOrderRestaurant}
     *
     * @return LimitOrderRestoran
     */
    private Function<Order, LimitOrderRestaurant> getLimitOrderRestoranFunction() {
        return order -> {
            List<LimitDishesOrder> dishesOrders = dishesOrderRepository.findAllByOrderId(order.getId())
                    .stream()
                    .map(LimitDishesOrder::new)
                    .toList();
            return new LimitOrderRestaurant(order, dishesOrders);
        };
    }
}