class Product {
  final int id;
  final String name;
  final String? description;
  final double price;
  final bool active;

  const Product({
    required this.id,
    required this.name,
    this.description,
    required this.price,
    required this.active,
  });

  factory Product.fromJson(Map<String, dynamic> json) => Product(
        id: (json['id'] as num).toInt(),
        name: json['name'] as String,
        description: json['description'] as String?,
        price: (json['price'] as num).toDouble(),
        active: json['active'] as bool? ?? true,
      );
}
