import 'package:flutter/material.dart';

import '../../../domain/entities/user.dart';

class UserListTile extends StatelessWidget {
  final User user;

  const UserListTile({Key? key, required this.user}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return ListTile(
      title: Text(user.name),
      subtitle: Text('User ID: ${user.id}'),
    );
  }
}