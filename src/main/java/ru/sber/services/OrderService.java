package ru.sber.services;

import ru.sber.entities.Order;
import ru.sber.models.LimitOrder;
import ru.sber.models.LimitOrderRestoran;

import java.util.List;
import java.util.Optional;

/**
 * Сервис для взаимодействия с {@link Order заказом}
 */
public interface OrderService {

    /**
     * Обновляет статус заказа
     *
     * @param id            id заказ
     * @param eStatusOrders статус
     * @return boolean
     */
    boolean updateOrderStatus(long id, String eStatusOrders);

    /**
     * Получает все заказы со статусом на рассмотрении и в процессе
     *
     * @return List<LimitOrder>
     */
    List<LimitOrderRestoran> getListOrder();

    /**
     * Ищет список заказов которые готовятся или уже готовы, но не доставляются
     *
     * @return список заказов
     */
    List<LimitOrderRestoran> findAllActiveOrder();

    /**
     * Ищет заказ по id
     *
     * @param id идентификатор заказа
     * @return заказ
     */
    Optional<LimitOrder> findOrderById(long id);

    /**
     * Отменяет заказ
     *
     * @param id id заказа
     * @param massage причина отказа
     */
    boolean cancellationOfOrderById(Long id, String massage);

    /**
     * Возвращает все заказы которые брал курьер
     *
     * @param id идентификатор курьера
     * @return список заказов курьера
     */
    List<LimitOrder> findOrdersByCourierId(long id);
}