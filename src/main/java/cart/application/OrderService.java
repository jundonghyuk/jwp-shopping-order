package cart.application;

import cart.domain.cartitem.CartItemRepository;
import cart.domain.order.OrderRepository;
import cart.domain.order.OrderItemRepository;
import cart.domain.point.PointRepository;
import cart.domain.product.ProductRepository;
import cart.domain.cartitem.CartItem;
import cart.domain.member.Member;
import cart.domain.order.Order;
import cart.domain.order.OrderItem;
import cart.domain.order.OrderPoint;
import cart.domain.point.Point;
import cart.domain.point.PointPolicy;
import cart.domain.time.Region;
import cart.domain.time.TimestampGenerator;
import cart.dto.order.OrderItemResponse;
import cart.dto.order.OrderRequest;
import cart.dto.order.OrderResponse;
import cart.exception.customexception.CartException;
import cart.exception.customexception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final PointRepository pointRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderPoint orderPoint;

    public OrderService(CartItemRepository cartItemRepository, ProductRepository productRepository, PointRepository pointRepository, OrderRepository orderRepository, OrderItemRepository orderItemRepository, PointPolicy pointPolicy) {
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.pointRepository = pointRepository;
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.orderPoint = new OrderPoint(pointPolicy);
    }

    @Transactional
    public Long orderCartItems(Member member, OrderRequest orderRequest) {
        // 0. 현재 주문 시간 구하기
        Timestamp createdAt = TimestampGenerator.getCurrentTime(Region.KOREA);

        // 1. 총 가격, 쓸 포인트, CartIds 꺼내기
        Long totalPrice = orderRequest.getTotalPrice();
        Long usePoint = orderRequest.getPoint();
        List<Long> cartIds = orderRequest.getCartIds();

        // 2. 포인트 조회[남은 포인트가 0초과 && 만료기한이 지나지 않음] 후 [보유 포인트가 사용포인트 보다 적거나 같다면 예외] 남은 포인트 업데이트
        usePoint(member, createdAt, usePoint);

        // 3. OrderPoint 를 통해 새로운 포인트와 유효기간 계산 후 삽입 [아이디 반환] [총 가격보다, 쓴 포인트가 많으면 예외]
        Point newPoint = orderPoint.earnPoint(member, usePoint, totalPrice, createdAt);
        Long pointId = pointRepository.createPoint(newPoint);

        // 4. CartIds 로 CartItems 조회 [회원의 소유가 아니거나 재고보다 많은 주문량 시 예외]
        List<CartItem> cartItems = findCartItems(member, cartIds);

        // 5. 상품_재고_업데이트
        updateProductStocks(cartItems);

        // 6. 장바구니에서 지우기
        removeCartItemsFromCart(cartItems);

        // 7. Order 객체 생성 후 삽입 [아이디 반환]
        Order order = new Order(member, usePoint, newPoint.getEarnedPoint(), createdAt);
        Long orderId = orderRepository.createOrder(order, pointId);

        // 8. CartItems 으로 OrderItems 생성 [주문 총가격 checkSum]
        order.addOrderItems(cartItems);
        order.checkTotalPrice(totalPrice);
        List<OrderItem> orderItems = order.getOrderItems();

        // 9. OrderItem 삽입
        for (OrderItem orderItem : orderItems) {
            orderItemRepository.createOrderItem(orderId, orderItem);
        }
        return orderId;
    }

    private void usePoint(Member member, Timestamp createdAt, Long usedPoint) {
        List<Point> points = pointRepository.findAllAvailablePointsByMemberId(member.getId(), createdAt);
        List<Point> updatePoints = orderPoint.usePoint(usedPoint, points);
        for (Point point : updatePoints) {
            pointRepository.updateLeftPoint(point);
        }
    }

    private List<CartItem> findCartItems(Member member, List<Long> cartIds) {
        List<CartItem> cartItems = new ArrayList<>();
        for (Long cartId : cartIds) {
            CartItem cartItem = cartItemRepository.findCartItemById(cartId)
                    .orElseThrow(() -> new CartException(ErrorCode.CART_ITEM_NOT_FOUND));
            if (cartItem.haveNoProduct()) {
                throw new CartException(ErrorCode.PRODUCT_NOT_FOUND);
            }
            cartItem.checkOwner(member);
            cartItem.checkQuantity();
            cartItems.add(cartItem);
        }
        return cartItems;
    }

    private void updateProductStocks(List<CartItem> cartItems) {
        for (CartItem cartItem : cartItems) {
            Long productId = cartItem.getProduct().getId();
            Long stock = cartItem.getProduct().getStock();
            Long quantity = cartItem.getQuantity();
            productRepository.updateStock(productId, stock - quantity);
        }
    }

    private void removeCartItemsFromCart(List<CartItem> cartItems) {
        List<Long> ids = cartItems.stream()
                .map(CartItem::getId)
                .collect(Collectors.toList());
        cartItemRepository.deleteAllIdIn(ids);
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrderById(Member member, Long orderId) {
        Order order = orderRepository.findOrderById(orderId)
                .orElseThrow(() -> new CartException(ErrorCode.ORDER_NOT_FOUND));
        order.checkOwner(member);

        List<OrderItem> orderItems = orderItemRepository.findOrderItemsByOrderId(orderId);

        List<OrderItemResponse> orderItemResponses = orderItems
                .stream()
                .map(OrderItemResponse::of)
                .collect(Collectors.toList());
        Long totalPrice = orderItems
                .stream()
                .mapToLong(OrderItem::getPrice)
                .sum();

        return new OrderResponse(
                orderId,
                order.getCreatedAt(),
                orderItemResponses,
                totalPrice,
                order.getUsedPoint(),
                order.getEarnedPoint());
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> getOrdersByMember(Member member) {
        List<OrderResponse> orderResponses = new ArrayList<>();
        List<Order> orders = orderRepository.findAllOrdersByMemberId(member.getId());
        for (Order order : orders) {
            List<OrderItem> orderItems = orderItemRepository.findOrderItemsByOrderId(order.getId());
            List<OrderItemResponse> orderItemResponses = orderItems
                    .stream()
                    .map(OrderItemResponse::of)
                    .collect(Collectors.toList());
            Long totalPrice = orderItems
                    .stream()
                    .mapToLong(OrderItem::getPrice)
                    .sum();
            orderResponses.add(
                    new OrderResponse(
                            order.getId(),
                            order.getCreatedAt(),
                            orderItemResponses,
                            totalPrice,
                            order.getUsedPoint(),
                            order.getEarnedPoint()
                    ));
        }
        return orderResponses;
    }
}
