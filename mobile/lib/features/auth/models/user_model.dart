class UserModel {
  final String id;
  final String fullName;
  final String email;
  final String role;
  final String? userCode;

  UserModel({
    required this.id,
    required this.fullName,
    required this.email,
    required this.role,
    this.userCode,
  });

  factory UserModel.fromJson(Map<String, dynamic> json) {
    return UserModel(
      id: json['id']?.toString() ?? '',
      fullName: json['fullName']?.toString() ?? '',
      email: json['email']?.toString() ?? '',
      role: json['role']?.toString() ?? 'GUARD',
      userCode: json['userCode']?.toString(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'fullName': fullName,
      'email': email,
      'role': role,
      'userCode': userCode,
    };
  }
}
