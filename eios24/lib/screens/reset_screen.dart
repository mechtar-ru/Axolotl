import 'package:flutter/material.dart';

class ResetScreen extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text('Reset Protocols')),
      body: GridView.builder(
        gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(crossAxisCount: 3),
        itemCount: 6,
        itemBuilder: (context, index) {
          return Card(
            child: Center(child: Text('Protocol ${index + 1}')));
        },
      ),
    );
  }
}